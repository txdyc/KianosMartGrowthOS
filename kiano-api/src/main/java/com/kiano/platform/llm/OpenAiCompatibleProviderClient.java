package com.kiano.platform.llm;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generic {@code POST /chat/completions} client for OpenAI-compatible providers
 * (DeepSeek, custom, ...). {@code request.effort()} is deliberately ignored -
 * there is no portable equivalent across OpenAI-compatible APIs. Structured
 * output is enforced locally: the JSON Schema is appended to the system prompt,
 * the reply is extracted with {@link JsonExtraction}, parsed into the requested
 * record and checked with {@link OutputValidator}; anything invalid triggers
 * one corrective retry (a user message naming the problem), and a second
 * failure is LLM_INVALID_OUTPUT. Every HTTP call - success or failure - writes
 * one llm_call row, and error text is scrubbed with {@link ErrorRedaction}.
 */
@Component
public class OpenAiCompatibleProviderClient implements ProviderClient {

    static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String SCHEMA_INSTRUCTION =
            "\n\nRespond with a single JSON object that conforms to this JSON Schema:\n";
    private static final int MAX_VALIDATION_ATTEMPTS = 2;
    private static final int MAX_HTTP_RETRIES = 2; // extra attempts after the first
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);

    private final LlmCallRecorder recorder;
    private final OutputSchemas outputSchemas;
    private final ObjectMapper mapper;
    private final Duration backoffDelay; // test hook; production is 1 second
    private final RestClient restClient;

    @Autowired
    public OpenAiCompatibleProviderClient(LlmCallRecorder recorder, OutputSchemas outputSchemas,
            ObjectMapper mapper) {
        this(recorder, outputSchemas, mapper, Duration.ofSeconds(1));
    }

    /** Package-private for tests: WireMock base urls and an instant backoff. */
    OpenAiCompatibleProviderClient(LlmCallRecorder recorder, OutputSchemas outputSchemas,
            ObjectMapper mapper, Duration backoffDelay) {
        this.recorder = recorder;
        this.outputSchemas = outputSchemas;
        this.mapper = mapper;
        this.backoffDelay = backoffDelay;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public ProviderKind kind() {
        return ProviderKind.OPENAI_COMPATIBLE;
    }

    @Override
    public <T> LlmResult<T> complete(LlmRequest<T> request, ResolvedRoute route)
            throws LlmException {
        String lastInvalid = null;
        for (int attempt = 0; attempt < MAX_VALIDATION_ATTEMPTS; attempt++) {
            Outcome<T> outcome = attempt(request, route, lastInvalid);
            if (outcome.result() != null) {
                return outcome.result();
            }
            lastInvalid = outcome.invalidReason();
        }
        throw new LlmException("LLM_INVALID_OUTPUT",
                "Provider returned invalid output twice"
                        + (lastInvalid == null ? "" : ": " + lastInvalid),
                false);
    }

    private <T> Outcome<T> attempt(LlmRequest<T> request, ResolvedRoute route,
            @Nullable String correction) throws LlmException {
        Response response = send(request, route, correction);
        if ("length".equals(response.finishReason())) {
            recorder.record(row(request, route, response, "TRUNCATED", "length", null));
            throw new LlmTruncatedException("Provider hit max_tokens (" + request.maxTokens()
                    + "); the output is incomplete");
        }
        if ("content_filter".equals(response.finishReason())) {
            recorder.record(row(request, route, response, "REFUSED", "content_filter", null));
            throw new LlmRefusedException("Provider refused the request (content filter)",
                    "content_filter");
        }
        String invalid = null;
        T parsed = null;
        Optional<String> json = JsonExtraction.firstObject(response.content());
        if (json.isEmpty()) {
            invalid = "no JSON object found in the reply";
        } else {
            try {
                parsed = mapper.readValue(json.get(), request.outputType());
                if (parsed == null) {
                    invalid = "the reply parsed to null";
                } else {
                    List<String> violations = OutputValidator.validate(parsed);
                    if (!violations.isEmpty()) {
                        invalid = violations.get(0);
                    }
                }
            } catch (RuntimeException ex) {
                invalid = "the reply is not valid JSON for the expected output: " + rootMessage(ex);
            }
        }
        String model = response.model() == null ? route.model() : response.model();
        BigDecimal cost = response.cost(route);
        if (invalid != null) {
            recorder.record(errorRow(request, route, response, cost, invalid));
            return Outcome.rejected(invalid);
        }
        long callId = recorder.record(new LlmCallRecorder.LlmCallRow(request.tenantId(),
                request.purpose(), model, "OK", response.input(), response.output(),
                response.cacheRead(), 0, cost, response.latencyMs(), response.stopReason(), null,
                route.providerLabel()));
        return Outcome.parsed(new LlmResult<>(parsed, model, response.input(), response.output(),
                cost, callId));
    }

    /** One HTTP call with internal backoff retries for transient failures. */
    private Response send(LlmRequest<?> request, ResolvedRoute route,
            @Nullable String correction) throws LlmException {
        long started = System.nanoTime();
        String body = buildBody(request, route, correction);
        for (int attempt = 0; ; attempt++) {
            try {
                String raw = restClient.post()
                        .uri(chatUrl(route))
                        .header("Authorization", "Bearer " + route.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                return parseResponse(raw, latencyMs(started));
            } catch (RestClientResponseException ex) {
                int status = ex.getStatusCode().value();
                String detail = ErrorRedaction.clean(errorBody(ex));
                if (status == 401 || status == 403) {
                    recorder.record(errorRow(request, route, latencyMs(started),
                            "LLM_CONFIG: " + detail));
                    throw new LlmException("LLM_CONFIG", "Provider rejected the API key (HTTP "
                            + status + "): " + detail, false);
                }
                if (status == 400 || status == 404 || status == 422) {
                    recorder.record(errorRow(request, route, latencyMs(started),
                            "LLM_BAD_REQUEST: " + detail));
                    throw new LlmException("LLM_BAD_REQUEST", "Provider rejected the request (HTTP "
                            + status + "): " + detail, false);
                }
                if (attempt >= MAX_HTTP_RETRIES) {
                    recorder.record(errorRow(request, route, latencyMs(started),
                            "LLM_UNAVAILABLE: " + detail));
                    throw new LlmException("LLM_UNAVAILABLE", "Provider unavailable after "
                            + (MAX_HTTP_RETRIES + 1) + " attempts (HTTP " + status + "): "
                            + detail, true);
                }
                // transient - every HTTP call writes a ledger row, including retries
                recorder.record(errorRow(request, route, latencyMs(started),
                        "LLM_RETRY: HTTP " + status + ": " + detail));
                backoff(attempt);
            } catch (ResourceAccessException ex) {
                String detail = ErrorRedaction.clean(ex.getMessage());
                if (attempt >= MAX_HTTP_RETRIES) {
                    recorder.record(errorRow(request, route, latencyMs(started),
                            "LLM_UNAVAILABLE: " + detail));
                    throw new LlmException("LLM_UNAVAILABLE", "Provider unreachable: " + detail,
                            true);
                }
                recorder.record(errorRow(request, route, latencyMs(started),
                        "LLM_RETRY: " + detail));
                backoff(attempt);
            }
        }
    }

    private Response parseResponse(@Nullable String raw, int latencyMs) {
        if (raw == null) {
            return Response.failure(latencyMs);
        }
        JsonNode tree;
        try {
            tree = mapper.readTree(raw);
        } catch (RuntimeException ex) {
            return Response.failure(latencyMs);
        }
        String model = tree.path("model").isTextual() ? tree.path("model").asText() : null;
        JsonNode usage = tree.path("usage");
        long input = usage.path("prompt_tokens").asLong(0);
        long output = usage.path("completion_tokens").asLong(0);
        long cacheRead = usage.path("prompt_cache_hit_tokens").asLong(0);
        JsonNode choice = tree.path("choices").path(0);
        String finishReason = choice.path("finish_reason").isTextual()
                ? choice.path("finish_reason").asText() : null;
        String content = choice.path("message").path("content").isTextual()
                ? choice.path("message").path("content").asText() : null;
        return new Response(model, input, output, cacheRead, latencyMs, content, finishReason);
    }

    private String buildBody(LlmRequest<?> request, ResolvedRoute route,
            @Nullable String correction) throws LlmException {
        ObjectNode root = mapper.createObjectNode()
                .put("model", route.model())
                .put("max_tokens", request.maxTokens())
                .put("stream", false);
        root.putObject("response_format").put("type", "json_object");
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content",
                request.system() + SCHEMA_INSTRUCTION
                        + outputSchemas.schemaFor(request.outputType()));
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        for (LlmImage image : request.images()) {
            content.addObject().put("type", "image_url")
                    .putObject("image_url")
                    .put("url", "data:image/jpeg;base64,"
                            + Base64.getEncoder().encodeToString(image.jpeg()));
        }
        content.addObject().put("type", "text").put("text", request.userText());
        if (correction != null) {
            messages.addObject().put("role", "user")
                    .put("content", "Your previous reply was not valid: " + correction
                            + ". Reply again with only the JSON object.");
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (RuntimeException ex) {
            throw new LlmException("LLM_BAD_REQUEST", "Cannot serialize the request body", false);
        }
    }

    private static String chatUrl(ResolvedRoute route) {
        String base = route.baseUrl();
        if (base == null) {
            return CHAT_COMPLETIONS_PATH;
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + CHAT_COMPLETIONS_PATH
                : base + CHAT_COMPLETIONS_PATH;
    }

    private void backoff(int attempt) {
        Duration delay = backoffDelay.multipliedBy(attempt == 0 ? 1 : 3);
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String errorBody(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        return body == null || body.isBlank() ? ex.getMessage() : body;
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private LlmCallRecorder.LlmCallRow row(LlmRequest<?> request, ResolvedRoute route,
            Response response, String status, @Nullable String stopReason,
            @Nullable String error) {
        return new LlmCallRecorder.LlmCallRow(request.tenantId(), request.purpose(),
                response.model() == null ? route.model() : response.model(), status,
                response.input(), response.output(), response.cacheRead(), 0,
                response.cost(route), response.latencyMs(), stopReason, error,
                route.providerLabel());
    }

    private LlmCallRecorder.LlmCallRow errorRow(LlmRequest<?> request, ResolvedRoute route,
            Response response, @Nullable BigDecimal cost, String error) {
        return new LlmCallRecorder.LlmCallRow(request.tenantId(), request.purpose(),
                response.model() == null ? route.model() : response.model(), "ERROR",
                response.input(), response.output(), response.cacheRead(), 0, cost,
                response.latencyMs(), null, error, route.providerLabel());
    }

    private LlmCallRecorder.LlmCallRow errorRow(LlmRequest<?> request, ResolvedRoute route,
            int latencyMs, String error) {
        return new LlmCallRecorder.LlmCallRow(request.tenantId(), request.purpose(),
                route.model(), "ERROR", 0, 0, 0, 0, null, latencyMs, null, error,
                route.providerLabel());
    }

    private static int latencyMs(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
    }

    /** Result of one validation attempt. */
    private record Outcome<T>(@Nullable LlmResult<T> result, @Nullable String invalidReason) {

        static <T> Outcome<T> parsed(LlmResult<T> result) {
            return new Outcome<>(result, null);
        }

        static <T> Outcome<T> rejected(String reason) {
            return new Outcome<>(null, reason);
        }
    }

    /** Normalized HTTP 200 response: model, usage, finish_reason and content. */
    private record Response(String model, long input, long output, long cacheRead, int latencyMs,
            @Nullable String content, @Nullable String finishReason) {

        static Response failure(int latencyMs) {
            return new Response(null, 0, 0, 0, latencyMs, null, null);
        }

        @Nullable
        String stopReason() {
            return finishReason;
        }

        BigDecimal cost(ResolvedRoute route) {
            return route.pricing().cost(input, output, cacheRead);
        }
    }
}