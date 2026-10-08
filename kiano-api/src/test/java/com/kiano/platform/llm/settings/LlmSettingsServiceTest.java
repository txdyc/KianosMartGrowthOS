package com.kiano.platform.llm.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.llm.LlmPurpose;
import com.kiano.platform.llm.Pricing;
import com.kiano.platform.llm.ProviderKind;
import com.kiano.platform.llm.settings.LlmSettingsService.ProviderInput;
import com.kiano.platform.llm.settings.LlmSettingsService.RouteInput;
import com.kiano.platform.web.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * LlmSettingsService validation and persistence: https base-url rule, duplicate
 * names, vision requirement for FACT_DRAFT, negative prices, provider-in-use
 * and key preservation on update.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LlmSettingsServiceTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LlmSettingsService service;

    @Autowired
    private com.kiano.platform.llm.LlmRouteStore store;

    private long tenantId;
    private CurrentUser owner;

    @BeforeEach
    void clean() {
        jdbc.update("delete from llm_route");
        jdbc.update("delete from llm_provider");
        jdbc.update("delete from audit_log");
        tenantId = jdbc.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        owner = new CurrentUser(1L, tenantId, Role.OWNER, "owner@kiano.local");
    }

    @Test
    void createProvider_requiresApiKey() {
        assertThatThrownBy(() -> service.createProvider(owner,
                new ProviderInput("DeepSeek", ProviderKind.OPENAI_COMPATIBLE,
                        "https://api.deepseek.com", null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("VALIDATION_FAILED");
                });
    }

    @Test
    void createOpenAiCompatible_requiresHttpsBaseUrl() {
        assertThatThrownBy(() -> service.createProvider(owner,
                new ProviderInput("Bad", ProviderKind.OPENAI_COMPATIBLE,
                        "http://api.example.com", "sk-one")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("LLM_BASE_URL_INVALID");
                });
        assertThatThrownBy(() -> service.createProvider(owner,
                new ProviderInput("Bad", ProviderKind.OPENAI_COMPATIBLE, null, "sk-one")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("LLM_BASE_URL_INVALID"));
    }

    @Test
    void createProvider_duplicateName_409() {
        service.createProvider(owner, new ProviderInput("DeepSeek",
                ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "sk-first"));

        assertThatThrownBy(() -> service.createProvider(owner,
                new ProviderInput("deepseek", ProviderKind.OPENAI_COMPATIBLE,
                        "https://api.deepseek.com", "sk-second")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("LLM_PROVIDER_NAME_TAKEN");
                });
    }

    @Test
    void saveFactDraftRoute_nonVision_422() {
        long providerId = service.createProvider(owner, new ProviderInput("DeepSeek",
                ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "sk-vision"));

        assertThatThrownBy(() -> service.saveRoute(owner, LlmPurpose.FACT_DRAFT,
                new RouteInput(providerId, "deepseek-v4-pro", false, new BigDecimal("1.32"),
                        new BigDecimal("3.96"), new BigDecimal("0.044"))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("ROUTE_REQUIRES_VISION");
                });
    }

    @Test
    void saveRoute_negativePrice_400() {
        long providerId = service.createProvider(owner, new ProviderInput("DeepSeek",
                ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "sk-price"));

        assertThatThrownBy(() -> service.saveRoute(owner, LlmPurpose.COPY,
                new RouteInput(providerId, "deepseek-flash", true, new BigDecimal("-0.01"),
                        BigDecimal.ONE, BigDecimal.ZERO)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getCode()).isEqualTo("VALIDATION_FAILED");
                });
    }

    @Test
    void deleteProviderInUse_409() {
        long providerId = service.createProvider(owner, new ProviderInput("DeepSeek",
                ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "sk-use"));
        service.saveRoute(owner, LlmPurpose.COPY, new RouteInput(providerId, "deepseek-flash",
                true, new BigDecimal("0.30"), new BigDecimal("1.20"), new BigDecimal("0.006")));

        assertThatThrownBy(() -> service.deleteProvider(owner, providerId))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("PROVIDER_IN_USE");
                });
    }

    @Test
    void updateProvider_blankKey_keepsKey() {
        long providerId = service.createProvider(owner, new ProviderInput("DeepSeek",
                ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "sk-keepme"));
        jdbc.update("insert into llm_route (tenant_id, purpose, provider_id, model, "
                + "supports_images, input_per_mtok, output_per_mtok, cache_read_per_mtok) "
                + "values (?, 'COPY', ?, 'deepseek-flash', true, 0.30, 1.20, 0.006)",
                tenantId, providerId);

        service.updateProvider(owner, providerId, new ProviderInput("DeepSeek-2",
                ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "  "));

        String stored = jdbc.queryForObject("select credentials_encrypted from llm_provider "
                + "where id = ?", String.class, providerId);
        assertThat(stored).doesNotContain("sk-keepme");
        // the blank key kept the stored one: the route still resolves the old key
        assertThat(store.resolve(tenantId, LlmPurpose.COPY).apiKey()).isEqualTo("sk-keepme");
        assertThat(service.get(owner).providers()).extracting(p -> p.name())
                .contains("DeepSeek-2");
    }
}