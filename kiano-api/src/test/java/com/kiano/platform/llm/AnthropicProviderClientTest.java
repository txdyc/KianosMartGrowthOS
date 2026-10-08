package com.kiano.platform.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThan;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.kiano.platform.llm.LlmCallRecorder.LlmCallRow;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * WireMock-backed tests for {@link AnthropicProviderClient}: request shape
 * (model, effort, images before text, no thinking config, server-side fallback),
 * stopReason handling (refusal / max_tokens), SDK retry exhaustion (529) and
 * auth errors, plus the llm_call ledger row on every path. Model, key and
 * pricing are taken from the resolved route, never from LlmProperties. The JDBC
 * recorder is mocked; {@link LlmCallRecorderTest} covers the real insert.
 */
class AnthropicProviderClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MESSAGES_PATH = "/v1/messages";

    private WireMockServer wireMock;
    private LlmCallRecorder recorder;
    private AnthropicProviderClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        recorder = mock(LlmCallRecorder.class);
        when(recorder.record(any(LlmCallRow.class))).thenReturn(42L);
        client = new AnthropicProviderClient(recorder, wireMock.baseUrl());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    record Probe(String model, Integer powerW) {
    }

    @Test
    void structuredOutput_parsesRecord_andRecordsUsageAndCost() throws Exception {
        stubResponse(200, "structured-ok.json");

        LlmResult<Probe> result = client.complete(request(LlmRequest.Effort.HIGH, 16000), route());

        assertThat(result.output()).isEqualTo(new Probe("MG-1500", 350));
        assertThat(result.model()).isEqualTo("claude-opus-5-5");
        assertThat(result.inputTokens()).isEqualTo(1200);
        assertThat(result.outputTokens()).isEqualTo(350);
        // in×4/1e6 + out×20/1e6 = 0.004800 + 0.007000
        assertThat(result.costUsd()).isEqualByComparingTo(new BigDecimal("0.011800"));
        assertThat(result.llmCallId()).isEqualTo(42L);

        ArgumentCaptor<LlmCallRow> row = ArgumentCaptor.forClass(LlmCallRow.class);
        verify(recorder).record(row.capture());
        assertThat(row.getValue().status()).isEqualTo("OK");
        assertThat(row.getValue().purpose()).isEqualTo(LlmPurpose.COPY);
        assertThat(row.getValue().model()).isEqualTo("claude-opus-5-5");
        assertThat(row.getValue().inputTokens()).isEqualTo(1200);
        assertThat(row.getValue().outputTokens()).isEqualTo(350);
        assertThat(row.getValue().stopReason()).isEqualTo("end_turn");
        assertThat(row.getValue().provider()).isEqualTo("ANTHROPIC");
    }

    @Test
    void request_sendsModelEffortImagesBeforeText_andNoThinkingDisabled() throws Exception {
        stubResponse(200, "structured-ok.json");
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x01, 0x02, 0x03};

        client.complete(new LlmRequest<>(7L, LlmPurpose.COPY, "sys", "describe",
                List.of(new LlmImage(jpeg, "P5"), new LlmImage(jpeg, "PROMO")),
                Probe.class, LlmRequest.Effort.HIGH, 16000), route());

        List<WireMockRequest> sent = requests();
        assertThat(sent).hasSize(1);
        JsonNode body = sent.get(0).body();
        assertThat(body.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("high");
        assertThat(body.path("system").asText()).isEqualTo("sys");
        assertThat(body.path("max_tokens").asLong()).isEqualTo(16000);
        JsonNode content = body.path("messages").get(0).path("content");
        assertThat(content.size()).isEqualTo(3);
        assertThat(content.get(0).path("type").asText()).isEqualTo("image");
        assertThat(content.get(0).path("source").path("type").asText()).isEqualTo("base64");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image");
        assertThat(content.get(2).path("type").asText()).isEqualTo("text");
        assertThat(content.get(2).path("text").asText()).isEqualTo("describe");
        // Opus 5.5 thinking stays default: no type:disabled, no budget_tokens.
        String raw = sent.get(0).rawBody();
        assertThat(raw).doesNotContain("disabled");
        assertThat(raw).doesNotContain("budget_tokens");
    }

    @Test
    void request_enablesServerSideFallback() throws Exception {
        stubResponse(200, "structured-ok.json");

        client.complete(request(LlmRequest.Effort.LOW, 100), route());

        List<WireMockRequest> sent = requests();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).betaHeaders())
                .anySatisfy(header -> assertThat(header).contains("server-side-fallback-2026-07-01"));
        assertThat(sent.get(0).body().path("fallbacks").asText()).isEqualTo("default");
    }

    @Test
    void refusal_throwsNonRetryableWithCategory_andRecordsRefused() {
        stubResponse(200, "refusal.json");

        assertThatThrownBy(() -> client.complete(request(LlmRequest.Effort.HIGH, 16000), route()))
                .isInstanceOfSatisfying(LlmRefusedException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("LLM_REFUSED");
                    assertThat(ex.retryable()).isFalse();
                    assertThat(ex.category()).isEqualTo("unsafe");
                });
        ArgumentCaptor<LlmCallRow> row = ArgumentCaptor.forClass(LlmCallRow.class);
        verify(recorder).record(row.capture());
        assertThat(row.getValue().status()).isEqualTo("REFUSED");
        assertThat(row.getValue().stopReason()).isEqualTo("refusal");
        // Refused calls are still billed for the tokens they used.
        assertThat(row.getValue().costUsd()).isNotNull();
    }

    @Test
    void maxTokens_throwsNonRetryableTruncated_andRecordsBilledCost() {
        stubResponse(200, "max-tokens.json");

        // Not retryable: re-sending the same request with the same budget would
        // truncate again and bill the full output each time.
        assertThatThrownBy(() -> client.complete(request(LlmRequest.Effort.HIGH, 16000), route()))
                .isInstanceOfSatisfying(LlmTruncatedException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("LLM_TRUNCATED");
                    assertThat(ex.retryable()).isFalse();
                });
        ArgumentCaptor<LlmCallRow> row = ArgumentCaptor.forClass(LlmCallRow.class);
        verify(recorder).record(row.capture());
        assertThat(row.getValue().status()).isEqualTo("TRUNCATED");
        // 90 input × $4/M + 1999 output × $20/M
        assertThat(row.getValue().costUsd()).isEqualByComparingTo(new BigDecimal("0.040340"));
    }

    @Test
    void overloaded_afterSdkRetries_throwsRetryableUnavailable() {
        wireMock.stubFor(post(urlPathEqualTo(MESSAGES_PATH))
                .willReturn(aResponse().withStatus(529)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture("overloaded-529.json"))));

        assertThatThrownBy(() -> client.complete(request(LlmRequest.Effort.HIGH, 16000), route()))
                .isInstanceOfSatisfying(LlmException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("LLM_UNAVAILABLE");
                    assertThat(ex.retryable()).isTrue();
                });
        wireMock.verify(moreThan(1), postRequestedFor(urlPathEqualTo(MESSAGES_PATH)));
    }

    @Test
    void unauthorized_throwsConfigError() {
        wireMock.stubFor(post(urlPathEqualTo(MESSAGES_PATH))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                                + "\"message\":\"Invalid API key\"}}")));

        assertThatThrownBy(() -> client.complete(request(LlmRequest.Effort.HIGH, 16000), route()))
                .isInstanceOfSatisfying(LlmException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("LLM_CONFIG");
                    assertThat(ex.retryable()).isFalse();
                });
        ArgumentCaptor<LlmCallRow> row = ArgumentCaptor.forClass(LlmCallRow.class);
        verify(recorder).record(row.capture());
        assertThat(row.getValue().status()).isEqualTo("ERROR");
    }

    @Test
    void missingApiKey_throwsNotConfigured_appStillStarts() {
        assertThatThrownBy(() -> client.complete(request(LlmRequest.Effort.HIGH, 16000), route("")))
                .isInstanceOfSatisfying(LlmException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("LLM_NOT_CONFIGURED");
                    assertThat(ex.retryable()).isFalse();
                });
        ArgumentCaptor<LlmCallRow> row = ArgumentCaptor.forClass(LlmCallRow.class);
        verify(recorder).record(row.capture());
        assertThat(row.getValue().status()).isEqualTo("ERROR");
        assertThat(row.getValue().error()).contains("ANTHROPIC_API_KEY");
    }

    @Test
    void usesRouteModelKeyAndPricing_notProperties() throws Exception {
        stubResponse(200, "structured-ok.json");
        ResolvedRoute sonnet = new ResolvedRoute(LlmPurpose.COPY, 1L, ProviderKind.ANTHROPIC,
                "route-provider", null, "route-key-456", "claude-sonnet-5-5", true,
                new Pricing(new BigDecimal("2.00"), new BigDecimal("10.00"),
                        new BigDecimal("0.20")), Instant.EPOCH, false);

        LlmResult<Probe> result = client.complete(request(LlmRequest.Effort.HIGH, 16000), sonnet);

        // 1200×2/1e6 + 350×10/1e6 = 0.002400 + 0.003500
        assertThat(result.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(result.costUsd()).isEqualByComparingTo(new BigDecimal("0.005900"));
        List<WireMockRequest> sent = requests();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).body().path("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(sent.get(0).apiKeyHeader()).isEqualTo("route-key-456");

        ArgumentCaptor<LlmCallRow> row = ArgumentCaptor.forClass(LlmCallRow.class);
        verify(recorder).record(row.capture());
        assertThat(row.getValue().model()).isEqualTo("claude-sonnet-5-5");
        assertThat(row.getValue().provider()).isEqualTo("ANTHROPIC");
    }

    @Test
    void differentKeys_useSeparateClients() throws Exception {
        stubResponse(200, "structured-ok.json");

        client.complete(request(LlmRequest.Effort.HIGH, 16000), route("key-A"));
        client.complete(request(LlmRequest.Effort.HIGH, 16000), route("key-B"));

        List<WireMockRequest> sent = requests();
        assertThat(sent).hasSize(2);
        assertThat(sent).extracting(WireMockRequest::apiKeyHeader)
                .containsExactlyInAnyOrder("key-A", "key-B");
    }

    // ---- helpers ----

    private static LlmRequest<Probe> request(LlmRequest.Effort effort, long maxTokens) {
        return new LlmRequest<>(7L, LlmPurpose.COPY, "sys", "describe", List.of(),
                Probe.class, effort, maxTokens);
    }

    /** Default-env-like route: claude-opus-5-5 at 4.00/20.00/0.20. */
    private static ResolvedRoute route() {
        return route("test-key-123");
    }

    private static ResolvedRoute route(String apiKey) {
        return new ResolvedRoute(LlmPurpose.COPY, 1L, ProviderKind.ANTHROPIC, "env-default",
                null, apiKey, "claude-opus-5-5", true,
                new Pricing(new BigDecimal("4.00"), new BigDecimal("20.00"),
                        new BigDecimal("0.20")), Instant.EPOCH, true);
    }

    private void stubResponse(int status, String fixture) {
        wireMock.stubFor(post(urlPathEqualTo(MESSAGES_PATH))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture(fixture))));
    }

    /** Captured requests for the last call, in order. */
    private List<WireMockRequest> requests() {
        return wireMock.getAllServeEvents().stream()
                .map(event -> new WireMockRequest(
                        MAPPER.readTree(event.getRequest().getBodyAsString()),
                        event.getRequest().getBodyAsString(),
                        event.getRequest().getHeaders().getHeader("anthropic-beta").values(),
                        event.getRequest().getHeader("x-api-key")))
                .toList();
    }

    private static String fixture(String name) {
        try (InputStream in = AnthropicProviderClientTest.class.getClassLoader()
                .getResourceAsStream("anthropic/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record WireMockRequest(JsonNode body, String rawBody, List<String> betaHeaders,
            @Nullable String apiKeyHeader) {
    }
}