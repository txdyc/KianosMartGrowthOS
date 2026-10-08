package com.kiano.platform.llm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.kiano.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * RoutingLlmGateway: per-purpose dispatch, env-default fallback, the vision
 * guard (LLM_MODEL_NO_VISION, ledger row, provider never called) and the
 * LLM_NOT_CONFIGURED check for a blank env key. Clients are injected per test;
 * keyRotation uses the real {@link AnthropicProviderClient} against WireMock to
 * prove a rotated key builds a fresh SDK client.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "kiano.llm.api-key=test-key-local")
class RoutingLlmGatewayTest {

    private static final Pricing ANTHROPIC_PRICE = new Pricing(new BigDecimal("4.00"),
            new BigDecimal("20.00"), new BigDecimal("0.20"));
    private static final Pricing DEEPSEEK_PRICE = new Pricing(new BigDecimal("0.30"),
            new BigDecimal("1.20"), new BigDecimal("0.006"));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LlmRouteStore store;

    private final ProviderClient anthropic = mock(ProviderClient.class);
    private final ProviderClient openai = mock(ProviderClient.class);
    private LlmCallRecorder recorder;
    private long tenantId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from llm_route");
        jdbc.update("delete from llm_provider");
        jdbc.update("delete from llm_call");
        tenantId = jdbc.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        recorder = new LlmCallRecorder(jdbc);
        when(anthropic.kind()).thenReturn(ProviderKind.ANTHROPIC);
        when(openai.kind()).thenReturn(ProviderKind.OPENAI_COMPATIBLE);
    }

    @Test
    void dispatchesByPurpose_toConfiguredKinds() throws Exception {
        long anthropicId = store.createProvider(tenantId, "Anthropic", ProviderKind.ANTHROPIC,
                null, "sk-anth-1");
        long deepSeekId = store.createProvider(tenantId, "DeepSeek",
                ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "sk-ds-1");
        store.saveRoute(tenantId, LlmPurpose.FACT_DRAFT, anthropicId, "claude-opus-5-5", true,
                ANTHROPIC_PRICE);
        store.saveRoute(tenantId, LlmPurpose.COPY, deepSeekId, "deepseek-flash", true,
                DEEPSEEK_PRICE);

        gateway().complete(request(LlmPurpose.FACT_DRAFT));
        gateway().complete(request(LlmPurpose.COPY));

        ArgumentCaptor<ResolvedRoute> fact = ArgumentCaptor.forClass(ResolvedRoute.class);
        verify(anthropic).complete(any(), fact.capture());
        assertThat(fact.getValue().purpose()).isEqualTo(LlmPurpose.FACT_DRAFT);
        assertThat(fact.getValue().providerId()).isEqualTo(anthropicId);
        assertThat(fact.getValue().kind()).isEqualTo(ProviderKind.ANTHROPIC);
        assertThat(fact.getValue().model()).isEqualTo("claude-opus-5-5");
        assertThat(fact.getValue().usingDefault()).isFalse();

        ArgumentCaptor<ResolvedRoute> copy = ArgumentCaptor.forClass(ResolvedRoute.class);
        verify(openai).complete(any(), copy.capture());
        assertThat(copy.getValue().purpose()).isEqualTo(LlmPurpose.COPY);
        assertThat(copy.getValue().providerId()).isEqualTo(deepSeekId);
        assertThat(copy.getValue().model()).isEqualTo("deepseek-flash");
        assertThat(copy.getValue().apiKey()).isEqualTo("sk-ds-1");
    }

    @Test
    void noRoute_usesEnvDefaultAnthropic() throws Exception {
        gateway().complete(request(LlmPurpose.FACT_DRAFT));

        ArgumentCaptor<ResolvedRoute> route = ArgumentCaptor.forClass(ResolvedRoute.class);
        verify(anthropic).complete(any(), route.capture());
        assertThat(route.getValue().usingDefault()).isTrue();
        assertThat(route.getValue().providerId()).isNull();
        assertThat(route.getValue().kind()).isEqualTo(ProviderKind.ANTHROPIC);
        assertThat(route.getValue().apiKey()).isEqualTo("test-key-local");
        assertThat(route.getValue().model()).isEqualTo("claude-opus-5-5");
        assertThat(route.getValue().supportsImages()).isTrue();
        assertThat(route.getValue().providerLabel()).isEqualTo("ANTHROPIC");
        verify(openai, never()).complete(any(), any());
    }

    @Test
    void imagesToNonVisionRoute_failsWithoutCallingProvider() throws LlmException {
        long providerId = store.createProvider(tenantId, "DeepSeek",
                ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "sk-ds-2");
        store.saveRoute(tenantId, LlmPurpose.FACT_DRAFT, providerId, "deepseek-v4-pro", false,
                DEEPSEEK_PRICE);
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x01};

        assertThatThrownBy(() -> gateway().complete(
                new LlmRequest<>(tenantId, LlmPurpose.FACT_DRAFT, "s", "u",
                        List.of(new LlmImage(jpeg, "P5")), Probe.class, LlmRequest.Effort.HIGH,
                        16000)))
                .isInstanceOfSatisfying(LlmException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("LLM_MODEL_NO_VISION");
                    assertThat(ex.retryable()).isFalse();
                });
        verify(anthropic, never()).complete(any(), any());
        verify(openai, never()).complete(any(), any());
        Integer errors = jdbc.queryForObject("select count(*) from llm_call "
                + "where status = 'ERROR' and error like '%LLM_MODEL_NO_VISION%'",
                Integer.class);
        assertThat(errors).isEqualTo(1);
    }

    @Test
    void keyRotation_rebuildsClient() throws Exception {
        WireMockServer wireMock = new WireMockServer(
                WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        try {
            wireMock.stubFor(post(urlPathEqualTo("/v1/messages"))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                                    {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[{"type":"text","text":"{\\"model\\":\\"MG-1500\\",\\"powerW\\":350}"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1200,"output_tokens":350,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}""")));

            long providerId = store.createProvider(tenantId, "Anthropic",
                    ProviderKind.ANTHROPIC, null, "old-secret-key");
            store.saveRoute(tenantId, LlmPurpose.FACT_DRAFT, providerId, "claude-opus-5-5", true,
                    ANTHROPIC_PRICE);
            RoutingLlmGateway gateway = new RoutingLlmGateway(store, recorder,
                    List.of(new AnthropicProviderClient(recorder, wireMock.baseUrl())));

            gateway.complete(request(LlmPurpose.FACT_DRAFT));
            store.updateProvider(tenantId, providerId, "Anthropic", null, "new-secret-key");
            gateway.complete(request(LlmPurpose.FACT_DRAFT));

            List<String> keys = wireMock.getAllServeEvents().stream()
                    .map(event -> event.getRequest().getHeader("x-api-key")).toList();
            // rotated key => a fresh SDK client => both keys observed
            assertThat(keys).contains("old-secret-key", "new-secret-key");
        } finally {
            wireMock.stop();
        }
    }

    @Test
    void defaultWithoutEnvKey_notConfigured() throws LlmException {
        ResolvedRoute blankDefault = new ResolvedRoute(LlmPurpose.FACT_DRAFT, null,
                ProviderKind.ANTHROPIC, "env-default", null, "", "claude-opus-5-5", true,
                ANTHROPIC_PRICE, Instant.EPOCH, true);

        assertThatThrownBy(() -> gateway().completeWith(request(LlmPurpose.FACT_DRAFT),
                blankDefault))
                .isInstanceOfSatisfying(LlmException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("LLM_NOT_CONFIGURED");
                    assertThat(ex.retryable()).isFalse();
                });
        verify(anthropic, never()).complete(any(), any());
    }

    record Probe(String model, Integer powerW) {
    }

    private static LlmRequest<Probe> request(LlmPurpose purpose) {
        return new LlmRequest<>(1L, purpose, "sys", "describe", List.of(), Probe.class,
                LlmRequest.Effort.HIGH, 16000);
    }

    private RoutingLlmGateway gateway() {
        return new RoutingLlmGateway(store, recorder, List.of(anthropic, openai));
    }
}