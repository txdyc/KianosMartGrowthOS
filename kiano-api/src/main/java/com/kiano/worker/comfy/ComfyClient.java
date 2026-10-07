package com.kiano.worker.comfy;

import com.kiano.worker.ExecutorException;
import com.kiano.worker.ExecutorUnavailableException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Thin client for the ComfyUI HTTP API: health probe, node inventory,
 * image upload (subfolder kiano), prompt queueing, history polling and
 * output download. Connection failures become ExecutorUnavailableException,
 * HTTP 5xx retryable errors, other HTTP errors non-retryable.
 */
@Component
@Profile("worker")
public class ComfyClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration OBJECT_INFO_CACHE = Duration.ofMinutes(5);

    private final RestClient api;
    private final RestClient probe;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private volatile @Nullable Set<String> cachedNodeClasses;
    private volatile long cachedNodeClassesAt;

    public ComfyClient(ComfyProperties properties) {
        this.api = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory(CONNECT_TIMEOUT, READ_TIMEOUT))
                .build();
        this.probe = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory(CONNECT_TIMEOUT, CONNECT_TIMEOUT))
                .build();
    }

    /** Health probe: any 2xx from /system_stats within 3 seconds counts as up. */
    public boolean systemStats() {
        try {
            return probe.get().uri("/system_stats")
                    .exchange((request, response) -> response.getStatusCode().is2xxSuccessful());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Node class names from /object_info, cached for five minutes. */
    public Set<String> objectInfoClasses() throws ExecutorException {
        Set<String> cached = cachedNodeClasses;
        if (cached != null && System.nanoTime() - cachedNodeClassesAt < OBJECT_INFO_CACHE.toNanos()) {
            return cached;
        }
        Response response = send(HttpMethod.GET, "/object_info",
                spec -> spec.uri("/object_info"));
        requireOk("GET /object_info", response);
        Set<String> classes = new HashSet<>();
        parse("GET /object_info", response).properties().forEach(entry -> classes.add(entry.getKey()));
        cachedNodeClasses = classes;
        cachedNodeClassesAt = System.nanoTime();
        return classes;
    }

    /** Uploads an image to ComfyUI's input dir and returns the LoadImage reference. */
    public String uploadImage(Path file) throws ExecutorException {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("image", new ByteArrayResource(readAllBytes(file)) {
            @Override
            public String getFilename() {
                return file.getFileName().toString();
            }
        });
        parts.add("overwrite", "true");
        parts.add("subfolder", "kiano");
        Response response = send(HttpMethod.POST, "/upload/image",
                spec -> spec.uri("/upload/image")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(parts));
        requireOk("POST /upload/image", response);
        JsonNode json = parse("POST /upload/image", response);
        String name = json.path("name").asString("");
        String subfolder = json.path("subfolder").asString("");
        if (name.isEmpty()) {
            throw new ExecutorException("POST /upload/image returned no name: " + bodySnippet(response), false);
        }
        return subfolder.isEmpty() ? name : subfolder + "/" + name;
    }

    /**
     * Queues a workflow; non-empty node_errors (schema violations, missing
     * models) are a non-retryable configuration problem.
     */
    public String queuePrompt(JsonNode workflow, String clientId) throws ExecutorException {
        ObjectNode body = mapper.createObjectNode();
        body.set("prompt", workflow);
        body.put("client_id", clientId);
        Response response = send(HttpMethod.POST, "/prompt",
                spec -> spec.uri("/prompt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(mapper.writeValueAsBytes(body)));
        JsonNode json = parse("POST /prompt", response);
        JsonNode nodeErrors = json.path("node_errors");
        if (nodeErrors.isObject() && !nodeErrors.isEmpty()) {
            throw new ExecutorException("ComfyUI rejected the workflow, node_errors=" + nodeErrors, false);
        }
        requireOk("POST /prompt", response);
        String promptId = json.path("prompt_id").asString("");
        if (promptId.isEmpty()) {
            throw new ExecutorException("POST /prompt returned no prompt_id: " + bodySnippet(response), false);
        }
        return promptId;
    }

    /** The prompt's history entry, or empty while it is still queued/running. */
    public Optional<JsonNode> history(String promptId) throws ExecutorException {
        Response response = send(HttpMethod.GET, "/history/" + promptId,
                spec -> spec.uri("/history/{id}", promptId));
        if (response.status() == 404) {
            return Optional.empty();
        }
        requireOk("GET /history", response);
        JsonNode entry = parse("GET /history", response).path(promptId);
        return entry.isObject() ? Optional.of(entry) : Optional.empty();
    }

    /** Downloads a finished output image from /view. */
    public byte[] view(String filename, String subfolder, String type) throws ExecutorException {
        Response response = send(HttpMethod.GET, "/view", spec -> spec.uri(builder -> builder
                .path("/view")
                .queryParam("filename", filename)
                .queryParam("subfolder", subfolder)
                .queryParam("type", type)
                .build()));
        requireOk("GET /view", response);
        return response.body();
    }

    /** Best effort: tells ComfyUI to abort the running prompt. */
    public void interrupt() {
        try {
            api.post().uri("/interrupt")
                    .exchange((request, response) -> response.getStatusCode().value());
        } catch (RuntimeException ex) {
            // best effort — the prompt times out either way
        }
    }

    private Response send(HttpMethod method, String path,
            Consumer<RestClient.RequestBodyUriSpec> customizer) throws ExecutorException {
        try {
            RestClient.RequestBodyUriSpec spec = api.method(method);
            customizer.accept(spec);
            return spec.exchange((request, response) -> new Response(
                    response.getStatusCode().value(), response.getBody().readAllBytes()));
        } catch (ResourceAccessException ex) {
            throw new ExecutorUnavailableException(
                    "ComfyUI is unreachable (" + method + " " + path + "): " + ex.getMessage());
        }
    }

    private static void requireOk(String what, Response response) throws ExecutorException {
        if (response.status() >= 500) {
            throw new ExecutorException(what + " failed: HTTP " + response.status()
                    + " " + bodySnippet(response), true);
        }
        if (response.status() >= 400) {
            throw new ExecutorException(what + " failed: HTTP " + response.status()
                    + " " + bodySnippet(response), false);
        }
    }

    private JsonNode parse(String what, Response response) throws ExecutorException {
        try {
            return mapper.readTree(response.body());
        } catch (JacksonException ex) {
            throw new ExecutorException(what + " returned invalid JSON: " + bodySnippet(response), true);
        }
    }

    private record Response(int status, byte[] body) {
    }

    private static String bodySnippet(Response response) {
        String text = new String(response.body(), StandardCharsets.UTF_8);
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }

    private static SimpleClientHttpRequestFactory factory(Duration connect, Duration read) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect);
        factory.setReadTimeout(read);
        return factory;
    }

    private static byte[] readAllBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
