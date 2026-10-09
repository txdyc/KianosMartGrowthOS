package com.kiano.content.video.h3;

import com.kiano.platform.llm.ErrorRedaction;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tentative MiniMax-style H3 client: submit task → query status → retrieve
 * file, with {@code Authorization: Bearer {key}}. Every path and field name
 * lives here plus in the WireMock recordings under {@code wiremock/h3}, so the
 * real API (rate limits, unit price, quotas - C5 plan Appendix A) replaces
 * this class and its fixtures in one place. Status mapping: 429 and 5xx are
 * retryable, other 4xx are not, and moderation refusals carry
 * {@code contentRejected}. Errors and logs never contain the key
 * ({@link ErrorRedaction}).
 */
@Component
@Profile("!worker")
public class MiniMaxH3Client implements H3Client {

    /** Tentative moderation wording; replaced by the real status codes in Appendix A. */
    private static final List<String> CONTENT_REJECTION_MARKERS = List.of(
            "sensitive", "moderation", "content policy", "policy violation", "prohibited", "violat");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final H3Properties properties;
    private final ObjectMapper mapper;
    private final RestClient api;
    private final RestClient files;

    public MiniMaxH3Client(H3Properties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        Duration timeout = properties.getApi().getTimeout();
        factory.setReadTimeout(timeout == null ? Duration.ofSeconds(30) : timeout);
        this.api = RestClient.builder().requestFactory(factory)
                .baseUrl(properties.getApi().getBaseUrl()).build();
        this.files = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String submit(H3Request request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getApi().getModel());
        body.put("prompt", request.prompt());
        body.put("first_frame_image", "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(request.firstFrameJpeg()));
        body.put("duration", request.durationSeconds());
        body.put("resolution", request.resolution());
        JsonNode response = parse(send(() -> api.post().uri(properties.getApi().getSubmitPath())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapper.writeValueAsString(body))
                .retrieve().body(String.class)));
        String taskId = text(response, "task_id");
        if (taskId.isBlank()) {
            throw new H3ClientException("H3 submit returned no task_id", false, false);
        }
        return taskId;
    }

    @Override
    public H3TaskStatus poll(String taskId) {
        JsonNode response = parse(send(() -> api.get()
                .uri(uriBuilder -> uriBuilder.path(properties.getApi().getQueryPath())
                        .queryParam("task_id", taskId).build())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve().body(String.class)));
        String status = text(response, "status");
        if ("success".equalsIgnoreCase(status)) {
            String fileRef = text(response, "file_id");
            if (fileRef.isBlank()) {
                throw new H3ClientException("H3 task " + taskId + " succeeded without a file_id",
                        false, false);
            }
            return new H3TaskStatus.Succeeded(fileRef);
        }
        if ("fail".equalsIgnoreCase(status)) {
            String reason = providerMessage(response, taskId);
            return new H3TaskStatus.Failed(reason, false, isContentRejected(reason));
        }
        return new H3TaskStatus.Pending();
    }

    @Override
    public void download(H3TaskStatus.Succeeded done, Path target) {
        JsonNode file = parse(send(() -> api.get()
                .uri(uriBuilder -> uriBuilder.path(properties.getApi().getFilePath())
                        .queryParam("file_id", done.fileRef()).build())
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .retrieve().body(String.class)));
        String url = file == null ? "" : file.path("file").path("download_url").asString();
        if (url.isBlank()) {
            throw new H3ClientException("H3 file " + done.fileRef() + " has no download_url",
                    false, false);
        }
        byte[] bytes = send(() -> files.get().uri(URI.create(url)).retrieve().body(byte[].class));
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Files.write(target, bytes == null ? new byte[0] : bytes);
        } catch (IOException ex) {
            throw new H3ClientException("Failed to write the H3 clip to " + target, false, false, ex);
        }
    }

    private <T> T send(Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException ex) {
            throw mapStatus(ex.getStatusCode(), ex.getResponseBodyAsString());
        } catch (ResourceAccessException ex) {
            throw new H3ClientException("H3 request failed: "
                    + ErrorRedaction.clean(ex.getMessage(), apiKey()), true, false, ex);
        }
    }

    private H3ClientException mapStatus(HttpStatusCode status, @Nullable String body) {
        String scrubbed = ErrorRedaction.clean(body, apiKey());
        boolean retryable = status.value() == 429 || status.is5xxServerError();
        boolean contentRejected = !retryable && isContentRejected(scrubbed);
        return new H3ClientException("H3 HTTP " + status.value()
                + (scrubbed == null || scrubbed.isBlank() ? "" : ": " + scrubbed),
                retryable, contentRejected);
    }

    private boolean isContentRejected(@Nullable String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return CONTENT_REJECTION_MARKERS.stream().anyMatch(lower::contains);
    }

    private String providerMessage(JsonNode response, String taskId) {
        String message = response == null ? ""
                : response.path("base_resp").path("status_msg").asString();
        return message.isBlank() ? "H3 task " + taskId + " failed" : message;
    }

    private static String text(@Nullable JsonNode node, String field) {
        return node == null ? "" : node.path(field).asString();
    }

    private @Nullable JsonNode parse(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : mapper.readTree(raw);
    }

    private String bearer() {
        return "Bearer " + apiKey();
    }

    private String apiKey() {
        return properties.getApi().getApiKey();
    }
}