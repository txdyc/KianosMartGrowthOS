package com.kiano.content.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.ads.AdCopyTaskHandlerTest.FakeGatewayConfig;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactsJson;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.llm.FakeLlmGateway;
import com.kiano.platform.llm.LlmPurpose;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ad copy generation: four AD_COPY text assets (one per hook) with plain-text
 * JSON bodies, invalid output that misses a hook, non-HERO / stale-fact skips
 * and single-hook regeneration.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FakeGatewayConfig.class})
class AdCopyTaskHandlerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeLlmGateway gateway;

    @Autowired
    private AdCopyTaskHandler handler;

    @Autowired
    private FactSheetService factSheetService;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper mapper;

    private long tenantId;
    private long storeId;
    private long productId;
    private int factVersion;

    @TestConfiguration
    static class FakeGatewayConfig {
        @Bean
        @Primary
        FakeLlmGateway fakeLlmGateway() {
            return new FakeLlmGateway();
        }
    }

    @BeforeEach
    void seed() {
        gateway.reset();
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from llm_call");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        long callId = jdbcTemplate.queryForObject(
                "insert into llm_call (tenant_id, purpose, model, input_tokens, output_tokens, "
                        + "cost_usd, latency_ms, status) values (?, 'AD_COPY', 'claude-opus-5-5', "
                        + "100, 50, 0.001400, 42, 'OK') returning id",
                Long.class, tenantId);
        gateway.setLlmCallId(callId);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, -6001, 'simple', 'MG-KTL17', 'Morgan 1.7L Kettle', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
        jdbcTemplate.update("insert into product_profile (product_id, tenant_id, content_tier) "
                + "values (?, ?, 'HERO')", productId, tenantId);
        long userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'adcopy-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        CurrentUser user = new CurrentUser(userId, tenantId, Role.OPERATOR, "adcopy-op@example.test");
        FactsJson facts = new FactsJson("MG-KTL17", "Electric Kettles", "1.7 L", 350,
                "220-240V", "Stainless steel", "Silver", "1 year", List.of("Kettle", "Base"),
                List.of("Auto shut-off"), List.of(), List.of());
        factSheetService.saveDraft(user, productId, facts,
                Map.of("model", com.kiano.content.facts.FieldSource.P5));
        factVersion = factSheetService.lock(user, productId, 1,
                Set.of("model", "capacity", "powerW", "voltage", "warranty", "inBox")).version();
    }

    private static AdCopyDraft sampleDraft() {
        return new AdCopyDraft(List.of(
                new AdCopyDraft.HookCopy(AdHook.PRICEHOOK, "Value kettle",
                        "Great kettle, fair price", "A 1.7 L kettle with auto shut-off"),
                new AdCopyDraft.HookCopy(AdHook.PROBLEM, "Tired of cold tea",
                        "Boils in minutes", "Never wait long for hot water"),
                new AdCopyDraft.HookCopy(AdHook.DEMO, "See it boil",
                        "Watch it in action", "Auto shut-off demo on video"),
                new AdCopyDraft.HookCopy(AdHook.TRUST, "You can trust it",
                        "Safe & reliable", "Stainless steel build")));
    }

    private TaskContext ctx(Map<String, Object> payload) {
        return new TaskContext(1L, tenantId, mapper.valueToTree(payload), 1);
    }

    @Test
    void generatesFourHookAssets_withPlainTextJsonBodies() throws Exception {
        gateway.queue(sampleDraft());

        Object out = handler.handle(ctx(Map.of("productId", productId, "factVersion",
                factVersion)));

        Map<?, ?> result = (Map<?, ?>) out;
        assertThat(result.get("generated")).isEqualTo(List.of("pricehook", "problem", "demo",
                "trust"));
        assertThat(gateway.last().purpose()).isEqualTo(LlmPurpose.AD_COPY);
        assertThat(gateway.last().maxTokens()).isEqualTo(8000);

        List<AssetEntity> assets = assetMapper.selectList(null);
        assertThat(assets).hasSize(4);
        for (AdHook hook : AdHook.values()) {
            AssetEntity asset = assets.stream()
                    .filter(a -> hook.wire().equals(a.getVariant())).findFirst().get();
            assertThat(asset.getSpecCode()).isEqualTo("AD_COPY");
            assertThat(asset.getKind()).isEqualTo("TEXT");
            assertThat(asset.getStatus()).isEqualTo("IN_REVIEW");
            assertThat(asset.getFactVersion()).isEqualTo(factVersion);
            // the stored body is a JSON object with the three plain fields
            JsonNode body = mapper.readTree(asset.getTextBody());
            assertThat(body.has("overlay")).isTrue();
            assertThat(body.has("headline")).isTrue();
            assertThat(body.has("primaryText")).isTrue();
            JsonNode content = mapper.readTree(asset.getContentJson());
            assertThat(content.path("hook").asText()).isEqualTo(hook.wire());
            assertThat(content.path("factVersion").asInt()).isEqualTo(factVersion);
        }
    }

    @Test
    void missingHookInOutput_invalidOutputNonRetryable() {
        gateway.queue(new AdCopyDraft(List.of(
                new AdCopyDraft.HookCopy(AdHook.PRICEHOOK, "a", "b", "c"),
                new AdCopyDraft.HookCopy(AdHook.PROBLEM, "a", "b", "c"),
                new AdCopyDraft.HookCopy(AdHook.TRUST, "a", "b", "c"))));

        assertThatThrownBy(() -> handler.handle(ctx(Map.of("productId", productId,
                "factVersion", factVersion))))
                .isInstanceOfSatisfying(NonRetryableTaskException.class,
                        ex -> assertThat(ex.getMessage()).startsWith("LLM_INVALID_OUTPUT"));
        assertThat(assetMapper.selectList(null)).isEmpty();
    }

    @Test
    void nonHero_skipped() throws Exception {
        jdbcTemplate.update("delete from product_profile where product_id = ?", productId);
        gateway.queue(sampleDraft());

        Object out = handler.handle(ctx(Map.of("productId", productId, "factVersion",
                factVersion)));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) out;
        assertThat(result).containsEntry("skipped", "NON_HERO");
        assertThat(assetMapper.selectList(null)).isEmpty();
        assertThat(gateway.callCount()).isZero();
    }

    @Test
    void staleFactVersion_skipped() throws Exception {
        gateway.queue(sampleDraft());

        Object out = handler.handle(ctx(Map.of("productId", productId, "factVersion",
                factVersion + 1)));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) out;
        assertThat(result).containsEntry("skipped", "STALE_FACTS");
        assertThat(assetMapper.selectList(null)).isEmpty();
        assertThat(gateway.callCount()).isZero();
    }

    @Test
    void onlyHook_savesSingleNewVersion() throws Exception {
        gateway.queue(sampleDraft());

        handler.handle(ctx(Map.of("productId", productId, "factVersion", factVersion,
                "onlyHook", "problem")));

        List<AssetEntity> assets = assetMapper.selectList(null);
        assertThat(assets).hasSize(1);
        assertThat(assets.get(0).getSpecCode()).isEqualTo("AD_COPY");
        assertThat(assets.get(0).getVariant()).isEqualTo("problem");
    }
}