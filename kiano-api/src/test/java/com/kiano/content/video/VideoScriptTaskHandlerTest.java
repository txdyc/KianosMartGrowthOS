package com.kiano.content.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactsJson;
import com.kiano.content.video.VideoScriptTaskHandlerTest.FakeGatewayConfig;
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
 * Video script generation (C5 T1): three VIDEO_SCRIPT text assets (one per
 * video type) with plain-text JSON bodies, invalid output that misses a type,
 * non-HERO / stale-fact skips and single-type regeneration.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FakeGatewayConfig.class})
class VideoScriptTaskHandlerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeLlmGateway gateway;

    @Autowired
    private VideoScriptTaskHandler handler;

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
        jdbcTemplate.update("delete from asset_review");
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
                        + "cost_usd, latency_ms, status) values (?, 'VIDEO_SCRIPT', 'claude-opus-5-5', "
                        + "100, 50, 0.001400, 42, 'OK') returning id",
                Long.class, tenantId);
        gateway.setLlmCallId(callId);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, -8001, 'simple', 'MG-KTL17', 'Morgan 1.7L Kettle', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
        jdbcTemplate.update("insert into product_profile (product_id, tenant_id, content_tier) "
                + "values (?, ?, 'HERO')", productId, tenantId);
        long userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'vscript-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        CurrentUser user = new CurrentUser(userId, tenantId, Role.OPERATOR,
                "vscript-op@example.test");
        FactsJson facts = new FactsJson("MG-KTL17", "Electric Kettles", "1.7 L", 350,
                "220-240V", "Stainless steel", "Silver", "1 year", List.of("Kettle", "Base"),
                List.of("Auto shut-off"), List.of(), List.of());
        factSheetService.saveDraft(user, productId, facts,
                Map.of("model", com.kiano.content.facts.FieldSource.P5));
        factVersion = factSheetService.lock(user, productId, 1,
                Set.of("model", "capacity", "powerW", "voltage", "warranty", "inBox")).version();
    }

    private static VideoScriptDraft sampleDraft() {
        return new VideoScriptDraft(List.of(
                new VideoScriptDraft.VideoScript(VideoType.DEMO, "Watch it boil in minutes",
                        List.of("1.7 L stainless steel", "Auto shut-off", "Fast heating")),
                new VideoScriptDraft.VideoScript(VideoType.PROBLEM, "Cold tea again",
                        List.of("Boils fast", "Keeps water hot", "Never wait long")),
                new VideoScriptDraft.VideoScript(VideoType.UNBOXING, "What is inside the box",
                        List.of("Kettle and base", "1.7 L capacity", "1 year warranty"))));
    }

    private TaskContext ctx(Map<String, Object> payload) {
        return new TaskContext(1L, tenantId, mapper.valueToTree(payload), 1);
    }

    @Test
    void generatesThreeTypeAssets_withPlainTextJsonBodies() throws Exception {
        gateway.queue(sampleDraft());

        Object out = handler.handle(ctx(Map.of("productId", productId, "factVersion",
                factVersion)));

        Map<?, ?> result = (Map<?, ?>) out;
        assertThat(result.get("generated")).isEqualTo(List.of("demo", "problem", "unboxing"));
        assertThat(gateway.last().purpose()).isEqualTo(LlmPurpose.VIDEO_SCRIPT);
        assertThat(gateway.last().maxTokens()).isEqualTo(8000);

        List<AssetEntity> assets = assetMapper.selectList(null);
        assertThat(assets).hasSize(3);
        for (VideoType type : VideoType.values()) {
            AssetEntity asset = assets.stream()
                    .filter(a -> type.wire().equals(a.getVariant())).findFirst().get();
            assertThat(asset.getSpecCode()).isEqualTo("VIDEO_SCRIPT");
            assertThat(asset.getKind()).isEqualTo("TEXT");
            assertThat(asset.getStatus()).isEqualTo("IN_REVIEW");
            assertThat(asset.getFactVersion()).isEqualTo(factVersion);
            // the stored body is a JSON object with hook and captions
            JsonNode body = mapper.readTree(asset.getTextBody());
            assertThat(body.has("hook")).isTrue();
            assertThat(body.path("captions").isArray()).isTrue();
            JsonNode content = mapper.readTree(asset.getContentJson());
            assertThat(content.path("type").asText()).isEqualTo(type.wire());
            assertThat(content.path("factVersion").asInt()).isEqualTo(factVersion);
        }
    }

    @Test
    void missingTypeInOutput_invalidOutputNonRetryable() {
        gateway.queue(new VideoScriptDraft(List.of(
                new VideoScriptDraft.VideoScript(VideoType.DEMO, "a", List.of("b", "c", "d")),
                new VideoScriptDraft.VideoScript(VideoType.PROBLEM, "a", List.of("b", "c", "d")))));

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
    void onlyType_savesSingleNewVersion() throws Exception {
        gateway.queue(sampleDraft());

        handler.handle(ctx(Map.of("productId", productId, "factVersion", factVersion,
                "onlyType", "problem")));

        List<AssetEntity> assets = assetMapper.selectList(null);
        assertThat(assets).hasSize(1);
        assertThat(assets.get(0).getSpecCode()).isEqualTo("VIDEO_SCRIPT");
        assertThat(assets.get(0).getVariant()).isEqualTo("problem");
    }
}
