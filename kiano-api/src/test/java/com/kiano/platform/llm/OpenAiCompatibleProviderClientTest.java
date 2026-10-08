package com.kiano.platform.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.kiano.content.copy.CopyDraft;
import com.kiano.platform.llm.LlmCallRecorder.LlmCallRow;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * WireMock-backed tests for {@link OpenAiCompatibleProviderClient}: request
 * shape (images before text, json_object, schema in system, Bearer auth),
 * local-schema validation with one corrective retry, finish_reason mapping,
 * HTTP backoff retries for 429/5xx and error redaction in the ledger.
 */
class OpenAiCompatibleProviderClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHAT_PATH = "/chat/completions";

    private WireMockServer wireMock;
    private LlmCallRecorder recorder;
    private OpenAiCompatibleProviderClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        recorder = mock(LlmCallRecorder.class);
        when(recorder.record(any(LlmCallRow.class))).thenReturn(42L);
        client = new OpenAiCompatibleProviderClient(recorder, new OutputSchemas(), MAPPER,
                Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    record Ping(String answer) {
    }

    record Picky(String model, Integer powerW) {
    }

    @Test
    void requestShape_imagesBeforeText_jsonObject_schemaInSystem_bearer() throws Exception {
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x01, 0x02, 0x03};
        stubBody(200, pickyResponse());
        ResolvedRoute route = route(LlmPurpose.FACT_DRAFT, true, "sk-test-key-123");

        client.complete(new LlmRequest<>(7L, LlmPurpose.FACT_DRAFT, "sys", "describe",
                List.of(new LlmImage(jpeg, "P5"), new LlmImage(jpeg, "PROMO")),
                Picky.class, LlmRequest.Effort.MEDIUM, 2048), route);

        List<WireMockRequest> sent = requests();
        assertThat(sent).hasSize(1);
        JsonNode body = sent.get(0).body();
        assertThat(sent.get(0).authorization()).isEqualTo("Bearer sk-test-key-123");
        assertThat(body.path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(body.path("max_tokens").asLong()).isEqualTo(2048);
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object");

        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(2);
        String system = messages.get(0).path("content").asText();
        assertThat(system).contains("Respond with a single JSON object");
        assertThat(system).contains("JSON Schema");
        assertThat(system).contains("powerW");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
        JsonNode content = messages.get(1).path("content");
        assertThat(content.get(0).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(0).path("image_url").path("url").asText())
                .startsWith("data:image/jpeg;base64,");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(2).path("type").asText()).isEqualTo("text");
        assertThat(content.get(2).path("text").asText()).isEqualTo("describe");
    }

    @Test
    void ok_parsesAndCostsWithCacheHits() throws Exception {
        stubJson(200, "ok.json");
        ResolvedRoute route = route(LlmPurpose.COPY, true, "sk-test-key-123");

        LlmResult<Ping> result = client.complete(request(LlmPurpose.COPY, Ping.class), route);

        assertThat(result.output()).isEqualTo(new Ping("pong"));
        assertThat(result.model()).isEqualTo("deepseek-flash");
        assertThat(result.inputTokens()).isEqualTo(1000);
        assertThat(result.outputTokens()).isEqualTo(500);
        // (1000-400)×0.30 + 400×0.006 + 500×1.20 → 0.000782
        assertThat(result.costUsd()).isEqualByComparingTo(new BigDecimal("0.000782"));
        assertThat(result.llmCallId()).isEqualTo(42L);

        ArgumentCaptor<LlmCallRow> row = ArgumentCaptor.forClass(LlmCallRow.class);
        verify(recorder).record(row.capture());
        assertThat(row.getValue().status()).isEqualTo("OK");
        assertThat(row.getValue().provider()).isEqualTo("OPENAI_COMPATIBLE:DeepSeek");
        assertThat(row.getValue().model()).isEqualTo("deepseek-flash");
        assertThat(row.getValue().inputTokens()).isEqualTo(1000);
        assertThat(row.getValue().outputTokens()).isEqualTo(500);
    }

    @Test
    void emptyContent_retriesOnce_thenSucceeds() throws Exception {
        scenario("empty-then-ok", "empty.json", "after-empty", "ok.json");
        ResolvedRoute route = route(LlmPurpose.COPY, true, "sk-test-key-123");

        LlmResult<Ping> result = client.complete(request(LlmPurpose.COPY, Ping.class), route);

        assertThat(result.output()).isEqualTo(new Ping("pong"));
        ArgumentCaptor<LlmCallRow> rows = ArgumentCaptor.forClass(LlmCallRow.class);
        verify(recorder, times(2)).record(rows.capture());
        assertThat(rows.getAllValues()).extracting(LlmCallRow::status)
                .containsExactly("ERROR", "OK");
        assertThat(rows.getAllValues().get(0).error()).contains("no JSON object found");

        List<WireMockRequest> sent = requests();
        assertThat(sent).hasSize(2);
        List<JsonNode> corrections = sent.stream().map(WireMockRequest::body)
                .filter(body -> hasCorrectionMessage(body)).toList();
        assertThat(corrections).hasSize(1);
        String correction = corrections.get(0).path("messages").get(3).path("content").asText();
        assertThat(correction).startsWith("Your previous reply was not valid:");
        assertThat(correction).endsWith("Reply again with only the JSON object.");
    }

    @Test
    void retry_carriesPreviousReplyAsAssistantTurn_betweenUserTurns() throws Exception {
        // Consecutive user messages are rejected by some OpenAI-compatible APIs,
        // and the model needs to see the reply it is being asked to fix.
        scenario("invalid-then-ok", "invalid-then-ok/invalid.json", "after-invalid", "ok.json");
        ResolvedRoute route = route(LlmPurpose.COPY, true, "sk-test-key-123");

        client.complete(request(LlmPurpose.COPY, Ping.class), route);

        JsonNode retry = retryRequest();
        assertThat(retry.path("messages")).extracting(node -> node.path("role").asText())
                .containsExactly("system", "user", "assistant", "user");
        String firstReply = MAPPER.readTree(fixture("invalid-then-ok/invalid.json"))
                .path("choices").path(0).path("message").path("content").asText();
        assertThat(retry.path("messages").get(2).path("content").asText()).isEqualTo(firstReply);
    }

    @Test
    void retryAfterEmptyReply_usesPlaceholderAssistantTurn() throws Exception {
        scenario("empty-then-ok-2", "empty.json", "after-empty", "ok.json");

        client.complete(request(LlmPurpose.COPY, Ping.class),
                route(LlmPurpose.COPY, true, "sk-test-key-123"));

        JsonNode assistant = retryRequest().path("messages").get(2);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        assertThat(assistant.path("content").asText()).isEqualTo("(empty reply)");
    }

    @Test
    void upstreamErrorEchoingAKeyWithoutSkPrefix_isRedacted() {
        String key = "3f9a1234abcd5678ef00";
        stubBody(401, "{\"error\":{\"message\":\"Invalid API key: " + key + "\"}}");

        assertThatThrownBy(() -> client.complete(request(LlmPurpose.COPY, Ping.class),
                route(LlmPurpose.COPY, true, key)))
                .isInstanceOfSatisfying(LlmException.class,
                        ex -> assertThat(ex.getMessage()).doesNotContain(key));
        ArgumentCaptor<LlmCallRow> row = ArgumentCaptor.forClass(LlmCallRow.class);
        verify(recorder).record(row.capture());
        assertThat(row.getValue().error()).doesNotContain(key);
    }

    @Test
    void invalidTwice_llmInvalidOutputNonRetryable() {
        stubJson(200, "invalid-then-ok/invalid.json");

        assertThatThrownBy(() -> client.complete(request(LlmPurpose.COPY, CopyDraft.class),
                route(LlmPurpose.COPY, true, "sk-test-key-123")))
                .isInstanceOfSatisfying(LlmException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("LLM_INVALID_OUTPUT");
                    assertThat(ex.retryable()).isFalse();
                });
        assertThat(requests()).hasSize(2);
        verify(recorder, times(2)).record(any(LlmCallRow.class));
    }

    @Test
    void missingRequiredField_triggersRetry() throws Exception {
        scenario("missing-then-ok", "missing-required.json", "after-missing",
                "invalid-then-ok/ok.json");
        ResolvedRoute route = route(LlmPurpose.COPY, true, "sk-test-key-123");

        LlmResult<CopyDraft> result = client.complete(request(LlmPurpose.COPY, CopyDraft.class),
                route);

        assertThat(result.output().title()).isEqualTo("Morgan MG-1500 Blender 400W - Powerful");
        ArgumentCaptor<LlmCallRow> rows = ArgumentCaptor.forClass(LlmCallRow.class);
        verify(recorder, times(2)).record(rows.capture());
        assertThat(rows.getAllValues().get(0).error()).contains("seoTitle is required");
    }

    @Test
    void fencedOrPrefixedJson_isExtracted() throws Exception {
        String fenced = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\""
                + "```json\\n{\\\"answer\\\":\\\"pong\\\"}\\n```\"}}],\"model\":\"deepseek-flash\","
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}";
        stubBody(200, fenced);

        LlmResult<Ping> result = client.complete(request(LlmPurpose.COPY, Ping.class),
                route(LlmPurpose.COPY, true, "sk-test-key-123"));

        assertThat(result.output()).isEqualTo(new Ping("pong"));
        verify(recorder).record(any(LlmCallRow.class));
    }

    @Test
    void finishLength_truncatedNonRetryable() {
        stubJson(200, "length.json");

        assertThatThrownBy(() -> client.complete(request(LlmPurpose.COPY, Ping.class),
                route(LlmPurpose.COPY, true, "sk-test-key-123")))
                .isInstanceOfSatisfying(LlmTruncatedException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("LLM_TRUNCATED");
                    assertThat(ex.retryable()).isFalse();
                });
        ArgumentCaptor<LlmCallRow> row = ArgumentCaptor.forClass(LlmCallRow.class);
        verify(recorder).record(row.capture());
        assertThat(row.getValue().status()).isEqualTo("TRUNCATED");
        assertThat(row.getValue().stopReason()).isEqualTo("length");
    }

    @Test
    void contentFilter_refused() {
        stubJson(200, "content-filter.json");

        assertThatThrownBy(() -> client.complete(request(LlmPurpose.COPY, Ping.class),
                route(LlmPurpose.COPY, true, "sk-test-key-123")))
                .isInstanceOfSatisfying(LlmRefusedException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("LLM_REFUSED");
                    assertThat(ex.category()).isEqualTo("content_filter");
                });
        ArgumentCaptor<LlmCallRow> row = ArgumentCaptor.forClass(LlmCallRow.class);
        verify(recorder).record(row.capture());
        assertThat(row.getValue().status()).isEqualTo("REFUSED");
        assertThat(row.getValue().stopReason()).isEqualTo("content_filter");
    }

    @Test
    void unauthorized_configError() {
        stubJson(401, "error-401.json");

        assertThatThrownBy(() -> client.complete(request(LlmPurpose.COPY, Ping.class),
                route(LlmPurpose.COPY, true, "sk-test-key-123")))
                .isInstanceOfSatisfying(LlmException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("LLM_CONFIG");
                    assertThat(ex.retryable()).isFalse();
                });
        // the key echo must never reach the ledger or the exception message
        ArgumentCaptor<LlmCallRow> row = ArgumentCaptor.forClass(LlmCallRow.class);
        verify(recorder).record(row.capture());
        assertThat(row.getValue().status()).isEqualTo("ERROR");
        assertThat(row.getValue().error()).doesNotContain("sk-abcdefgh12345678");
        assertThat(row.getValue().error()).doesNotContain("Bearer sk-");
    }

    @Test
    void upstreamErrorWithKeyEcho_isRedactedInLedger() {
        stubJson(401, "error-401.json");

        assertThatThrownBy(() -> client.complete(request(LlmPurpose.COPY, Ping.class),
                route(LlmPurpose.COPY, true, "sk-test-key-123")))
                .isInstanceOfSatisfying(LlmException.class,
                        ex -> assertThat(ex.getMessage()).doesNotContain("sk-abcdefgh12345678"));
    }

    @Test
    void rateLimitedThenOk_retriedInsideClient() throws Exception {
        scenario("rate-limit", "429", "after-rate-limit", "ok.json");
        ResolvedRoute route = route(LlmPurpose.COPY, true, "sk-test-key-123");

        LlmResult<Ping> result = client.complete(request(LlmPurpose.COPY, Ping.class), route);

        assertThat(result.output()).isEqualTo(new Ping("pong"));
        assertThat(requests()).hasSize(2);
        ArgumentCaptor<LlmCallRow> rows = ArgumentCaptor.forClass(LlmCallRow.class);
        verify(recorder, times(2)).record(rows.capture());
        assertThat(rows.getAllValues()).extracting(LlmCallRow::status)
                .containsExactly("ERROR", "OK");
    }

    @Test
    void rateLimitedAlways_unavailableRetryable() {
        wireMock.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"overloaded\"}}")));

        assertThatThrownBy(() -> client.complete(request(LlmPurpose.COPY, Ping.class),
                route(LlmPurpose.COPY, true, "sk-test-key-123")))
                .isInstanceOfSatisfying(LlmException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("LLM_UNAVAILABLE");
                    assertThat(ex.retryable()).isTrue();
                });
        assertThat(requests()).hasSize(3);
        verify(recorder, times(3)).record(any(LlmCallRow.class));
    }

    // ---- helpers ----

    private static <T> LlmRequest<T> request(LlmPurpose purpose, Class<T> outputType) {
        return new LlmRequest<>(7L, purpose, "sys", "answer", List.of(), outputType,
                LlmRequest.Effort.LOW, 1024);
    }

    private ResolvedRoute route(LlmPurpose purpose, boolean vision, String apiKey) {
        return new ResolvedRoute(purpose, 1L, ProviderKind.OPENAI_COMPATIBLE, "DeepSeek",
                wireMock.baseUrl(), apiKey, "deepseek-flash", vision,
                new Pricing(new BigDecimal("0.30"), new BigDecimal("1.20"),
                        new BigDecimal("0.006")), Instant.EPOCH, false);
    }

    private static String pickyResponse() {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + "\"{\\\"model\\\":\\\"MG-1500\\\",\\\"powerW\\\":350}\"}}],"
                + "\"model\":\"deepseek-flash\",\"usage\":{\"prompt_tokens\":10,"
                + "\"completion_tokens\":2,\"prompt_cache_hit_tokens\":0}}";
    }

    private void stubJson(int status, String fixturePath) {
        wireMock.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture(fixturePath))));
    }

    private void stubBody(int status, String body) {
        wireMock.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    /** Two-step WireMock scenario: first {@code firstBody}, then {@code okFixture}. */
    private void scenario(String name, String firstBody, String nextState, String okFixture) {
        wireMock.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .inScenario(name)
                .whenScenarioStateIs("Started")
                .willReturn(aResponse()
                        .withStatus("429".equals(firstBody) ? 429 : 200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("429".equals(firstBody)
                                ? "{\"error\":{\"message\":\"rate limited\"}}"
                                : fixture(firstBody)))
                .willSetStateTo(nextState));
        wireMock.stubFor(post(urlPathEqualTo(CHAT_PATH))
                .inScenario(name)
                .whenScenarioStateIs(nextState)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture(okFixture))));
    }

    private static String fixture(String name) {
        try (InputStream in = OpenAiCompatibleProviderClientTest.class.getClassLoader()
                .getResourceAsStream("openai/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture openai/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean hasCorrectionMessage(JsonNode body) {
        JsonNode messages = body.path("messages");
        return messages.size() >= 3
                && messages.get(messages.size() - 1).path("role").asText().equals("user")
                && messages.get(messages.size() - 1).path("content").asText()
                        .startsWith("Your previous reply was not valid:");
    }

    /** The validation retry: the request carrying the assistant + correction turns. */
    private JsonNode retryRequest() {
        return requests().stream().map(WireMockRequest::body)
                .filter(body -> body.path("messages").size() == 4)
                .findFirst().orElseThrow(() -> new AssertionError("no retry request was sent"));
    }

    private List<WireMockRequest> requests() {
        return wireMock.getAllServeEvents().stream()
                .map(event -> new WireMockRequest(
                        MAPPER.readTree(event.getRequest().getBodyAsString()),
                        event.getRequest().getHeader("Authorization")))
                .toList();
    }

    private record WireMockRequest(JsonNode body, String authorization) {
    }
}