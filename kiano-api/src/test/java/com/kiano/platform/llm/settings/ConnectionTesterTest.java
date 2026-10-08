package com.kiano.platform.llm.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.kiano.TestcontainersConfiguration;
import com.kiano.platform.llm.LlmPurpose;
import com.kiano.platform.llm.LlmResult;
import com.kiano.platform.llm.RoutingLlmGateway;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.llm.LlmRequest;

/**
 * ConnectionTester: real calls for both purposes (vision probe for FACT_DRAFT,
 * requirement of "pong" for COPY), route fallback to the env default and
 * redacted provider errors instead of exceptions. The router is replaced by a
 * mock so no real API is contacted.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ConnectionTesterTest {

    private static final CurrentUser OWNER = new CurrentUser(1L, 1L, Role.OWNER, "owner@kiano.local");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConnectionTester tester;

    @MockitoBean
    private RoutingLlmGateway gateway;

    @BeforeEach
    void clean() {
        jdbc.update("delete from llm_route");
        jdbc.update("delete from llm_provider");
        jdbc.update("delete from llm_call");
    }

    @Test
    void factDraft_sendsProbeImage_okWhenAnswerIsK() throws Exception {
        given(gateway.completeWith(any(), any()))
                .willReturn(new LlmResult<>(new ConnectionTester.Ping("K"), "deepseek-flash",
                        100, 20, new BigDecimal("0.000050"), 1L));

        ConnectionTester.TestResult result = tester.test(OWNER, LlmPurpose.FACT_DRAFT);

        assertThat(result.ok()).isTrue();
        assertThat(result.model()).isEqualTo("deepseek-flash");

        ArgumentCaptor<LlmRequest<?>> request = ArgumentCaptor.forClass(LlmRequest.class);
        ArgumentCaptor.forClass(com.kiano.platform.llm.ResolvedRoute.class);
        org.mockito.Mockito.verify(gateway).completeWith(request.capture(), any());
        assertThat(request.getValue().purpose()).isEqualTo(LlmPurpose.CONNECTION_TEST);
        assertThat(request.getValue().images()).hasSize(1);
        assertThat(request.getValue().outputType()).isEqualTo(ConnectionTester.Ping.class);
    }

    @Test
    void factDraft_wrongLetter_visionCheckFailed() throws Exception {
        given(gateway.completeWith(any(), any()))
                .willReturn(new LlmResult<>(new ConnectionTester.Ping("B"), "deepseek-flash",
                        100, 20, null, 1L));

        ConnectionTester.TestResult result = tester.test(OWNER, LlmPurpose.FACT_DRAFT);

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("LLM_VISION_CHECK_FAILED");
        assertThat(result.message()).contains("B");
    }

    @Test
    void copy_okWithoutImage() throws Exception {
        given(gateway.completeWith(any(), any()))
                .willReturn(new LlmResult<>(new ConnectionTester.Ping("pong"), "deepseek-flash",
                        100, 20, null, 1L));

        ConnectionTester.TestResult result = tester.test(OWNER, LlmPurpose.COPY);

        assertThat(result.ok()).isTrue();
        ArgumentCaptor<LlmRequest<?>> request = ArgumentCaptor.forClass(LlmRequest.class);
        org.mockito.Mockito.verify(gateway).completeWith(request.capture(), any());
        assertThat(request.getValue().images()).isEmpty();
    }

    @Test
    void providerError_returnsOkFalseWithRedactedMessage() throws Exception {
        given(gateway.completeWith(any(), any())).willThrow(new com.kiano.platform.llm.LlmException(
                "LLM_CONFIG", "Provider rejected the API key (HTTP 401): Bearer sk-abcdefgh12345678",
                false));

        ConnectionTester.TestResult result = tester.test(OWNER, LlmPurpose.COPY);

        assertThat(result.ok()).isFalse();
        assertThat(result.code()).isEqualTo("LLM_CONFIG");
        assertThat(result.message()).doesNotContain("sk-abcdefgh12345678");
        assertThat(result.message()).doesNotContain("Bearer sk-");
    }

    @Test
    void recordsPurposeConnectionTest() throws Exception {
        given(gateway.completeWith(any(), any()))
                .willReturn(new LlmResult<>(new ConnectionTester.Ping("pong"), "deepseek-flash",
                        100, 20, null, 1L));

        tester.test(OWNER, LlmPurpose.COPY);

        ArgumentCaptor<LlmRequest<?>> request = ArgumentCaptor.forClass(LlmRequest.class);
        org.mockito.Mockito.verify(gateway).completeWith(request.capture(), any());
        assertThat(request.getValue().purpose()).isEqualTo(LlmPurpose.CONNECTION_TEST);
        assertThat(request.getValue().maxTokens()).isEqualTo(1024);
        assertThat(request.getValue().effort()).isEqualTo(LlmRequest.Effort.LOW);
    }
}