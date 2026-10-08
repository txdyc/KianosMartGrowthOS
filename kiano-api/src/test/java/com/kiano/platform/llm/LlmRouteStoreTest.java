package com.kiano.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.kiano.TestcontainersConfiguration;
import com.kiano.platform.crypto.CredentialCipher;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * LlmRouteStore: encrypted provider storage (ciphertext, never the key), the
 * env-default Anthropic route when unconfigured, route upsert/fallback, the
 * updatedAt = later(route, provider) rule for client caching, and Per-mtok
 * pricing arithmetic.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LlmRouteStoreTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LlmRouteStore store;

    @Autowired
    private LlmProperties properties;

    @MockitoSpyBean
    private CredentialCipher cipher;

    private long tenantId;

    @BeforeEach
    void seed() {
        jdbc.update("delete from llm_route");
        jdbc.update("delete from llm_provider");
        jdbc.update("delete from llm_call");
        tenantId = jdbc.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
    }

    @Test
    void resolve_withoutRoute_returnsEnvDefaultAnthropic() {
        ResolvedRoute route = store.resolve(tenantId, LlmPurpose.FACT_DRAFT);

        assertThat(route.usingDefault()).isTrue();
        assertThat(route.providerId()).isNull();
        assertThat(route.kind()).isEqualTo(ProviderKind.ANTHROPIC);
        assertThat(route.model()).isEqualTo(properties.getModel());
        assertThat(route.apiKey()).isEqualTo(properties.getApiKey());
        assertThat(route.supportsImages()).isTrue();
        assertThat(route.updatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(route.providerLabel()).isEqualTo("ANTHROPIC");
        assertThat(route.pricing().inputPerMtok()).isEqualByComparingTo("4.0");
        assertThat(route.pricing().outputPerMtok()).isEqualByComparingTo("20.0");
        assertThat(route.pricing().cacheReadPerMtok()).isEqualByComparingTo("0.2");
    }

    @Test
    void createProvider_storesCiphertextNotKey() {
        long id = store.createProvider(tenantId, "DeepSeek", ProviderKind.OPENAI_COMPATIBLE,
                "https://api.deepseek.com", "sk-topsecret-123456");

        String stored = jdbc.queryForObject(
                "select credentials_encrypted from llm_provider where id = ?", String.class, id);
        assertThat(stored).startsWith("v1:");
        assertThat(stored).doesNotContain("sk-topsecret-123456");
    }

    @Test
    void saveRoute_thenResolve_decryptsKeyAndCarriesPricing() {
        long providerId = store.createProvider(tenantId, "DeepSeek",
                ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "sk-ds-abcdef1234");
        store.saveRoute(tenantId, LlmPurpose.COPY, providerId, "deepseek-flash", true,
                new Pricing(new BigDecimal("0.30"), new BigDecimal("1.20"),
                        new BigDecimal("0.006")));

        ResolvedRoute route = store.resolve(tenantId, LlmPurpose.COPY);

        assertThat(route.usingDefault()).isFalse();
        assertThat(route.providerId()).isEqualTo(providerId);
        assertThat(route.kind()).isEqualTo(ProviderKind.OPENAI_COMPATIBLE);
        assertThat(route.apiKey()).isEqualTo("sk-ds-abcdef1234");
        assertThat(route.model()).isEqualTo("deepseek-flash");
        assertThat(route.supportsImages()).isTrue();
        assertThat(route.pricing().inputPerMtok()).isEqualByComparingTo("0.30");
        assertThat(route.providerLabel()).isEqualTo("OPENAI_COMPATIBLE:DeepSeek");
    }

    @Test
    void resolve_updatedAt_isLaterOfRouteAndProvider() throws Exception {
        long providerId = store.createProvider(tenantId, "DeepSeek",
                ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "sk-one-1");
        Thread.sleep(5);
        store.saveRoute(tenantId, LlmPurpose.FACT_DRAFT, providerId, "deepseek-v4-pro", false,
                new Pricing(new BigDecimal("1.32"), new BigDecimal("3.96"),
                        new BigDecimal("0.044")));

        ResolvedRoute route = store.resolve(tenantId, LlmPurpose.FACT_DRAFT);
        // the route was written after the provider, so its updated_at should win
        long routeUpdated = jdbc.queryForObject("select extract(epoch from updated_at) * 1000 "
                + "from llm_route where tenant_id = ?", long.class, tenantId);
        assertThat(route.updatedAt().toEpochMilli()).isGreaterThanOrEqualTo(routeUpdated - 1);
    }

    @Test
    void updateProvider_blankKey_keepsOldKey_bumpsUpdatedAt() throws Exception {
        long providerId = store.createProvider(tenantId, "DeepSeek",
                ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "sk-old-secret");
        long before = jdbc.queryForObject("select extract(epoch from updated_at) * 1000 "
                + "from llm_provider where id = ?", long.class, providerId);
        Thread.sleep(5);

        store.updateProvider(tenantId, providerId, "DeepSeek-2", "https://api.deepseek.com", "");

        ResolvedRoute route = store.resolve(tenantId, LlmPurpose.CONNECTION_TEST);
        assertThat(jdbc.queryForObject("select credentials_encrypted from llm_provider where id = ?",
                String.class, providerId)).doesNotContain("sk-old-secret");
        jdbc.update("insert into llm_route (tenant_id, purpose, provider_id, model, "
                + "supports_images, input_per_mtok, output_per_mtok, cache_read_per_mtok) "
                + "values (?, 'COPY', ?, 'deepseek-flash', true, 0.30, 1.20, 0.006)",
                tenantId, providerId);
        assertThat(store.resolve(tenantId, LlmPurpose.COPY).apiKey()).isEqualTo("sk-old-secret");
        long after = jdbc.queryForObject("select extract(epoch from updated_at) * 1000 "
                + "from llm_provider where id = ?", long.class, providerId);
        assertThat(after).isGreaterThan(before);
    }

    @Test
    void deleteRoute_fallsBackToDefault() {
        long providerId = store.createProvider(tenantId, "DeepSeek",
                ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "sk-x-1");
        store.saveRoute(tenantId, LlmPurpose.COPY, providerId, "deepseek-flash", true,
                new Pricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));

        store.deleteRoute(tenantId, LlmPurpose.COPY);

        assertThat(store.resolve(tenantId, LlmPurpose.COPY).usingDefault()).isTrue();
    }

    @Test
    void adCopy_resolvesCopyRoute_keepsPurpose() {
        long providerId = store.createProvider(tenantId, "DeepSeek",
                ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "sk-ad-1");
        store.saveRoute(tenantId, LlmPurpose.COPY, providerId, "deepseek-flash", true,
                new Pricing(new BigDecimal("0.30"), new BigDecimal("1.20"),
                        new BigDecimal("0.006")));

        ResolvedRoute ad = store.resolve(tenantId, LlmPurpose.AD_COPY);

        assertThat(ad.purpose()).isEqualTo(LlmPurpose.AD_COPY);
        assertThat(ad.kind()).isEqualTo(ProviderKind.OPENAI_COMPATIBLE);
        assertThat(ad.model()).isEqualTo("deepseek-flash");
        assertThat(ad.apiKey()).isEqualTo("sk-ad-1");
        assertThat(ad.usingDefault()).isFalse();

        // no route at all → env default keeps the AD_COPY purpose too
        store.deleteRoute(tenantId, LlmPurpose.COPY);
        ResolvedRoute defaultAd = store.resolve(tenantId, LlmPurpose.AD_COPY);
        assertThat(defaultAd.usingDefault()).isTrue();
        assertThat(defaultAd.purpose()).isEqualTo(LlmPurpose.AD_COPY);
    }

    @Test
    void listRoutes_alwaysHasBothPurposes() {
        long providerId = store.createProvider(tenantId, "DeepSeek",
                ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com", "sk-x-2");
        store.saveRoute(tenantId, LlmPurpose.FACT_DRAFT, providerId, "deepseek-flash", true,
                new Pricing(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO));

        List<LlmRouteStore.RouteView> routes = store.listRoutes(tenantId);

        assertThat(routes).hasSize(2);
        LlmRouteStore.RouteView facts = routes.stream()
                .filter(r -> r.purpose() == LlmPurpose.FACT_DRAFT).findFirst().get();
        assertThat(facts.usingDefault()).isFalse();
        LlmRouteStore.RouteView copy = routes.stream()
                .filter(r -> r.purpose() == LlmPurpose.COPY).findFirst().get();
        assertThat(copy.usingDefault()).isTrue();
    }

    @Test
    void pricing_cost_subtractsCacheHitsFromInput() {
        Pricing pricing = new Pricing(new BigDecimal("0.30"), new BigDecimal("1.20"),
                new BigDecimal("0.006"));

        // (1000-400)×0.30 + 400×0.006 + 500×1.20 = 180 + 2.4 + 600 = 782.4 /1e6
        BigDecimal cost = pricing.cost(1000, 500, 400);

        assertThat(cost).isEqualByComparingTo("0.000782");
    }

    @Test
    void createProvider_isAtomic_noRowLeftWhenKeyEncryptionFails() {
        doThrow(new IllegalStateException("cipher down")).when(cipher).encrypt(anyString(), anyString());

        assertThatThrownBy(() -> store.createProvider(tenantId, "Broken",
                ProviderKind.OPENAI_COMPATIBLE, "https://x.test", "sk-secret-123"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("select count(*) from llm_provider where name = 'Broken'",
                Integer.class)).isZero();
    }
}
