package com.kiano.content.copy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.copy.CopyGenerationTaskHandlerTest.FakeGatewayConfig;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactsJson;
import com.kiano.content.policy.PolicyService;
import com.kiano.content.policy.PolicyService.SectionText;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.llm.FakeLlmGateway;
import com.kiano.platform.llm.LlmRefusedException;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import java.util.EnumMap;
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
 * Copy generation: six IN_REVIEW text assets with fact version + provenance,
 * stale-fact skip, per-spec regeneration and refusal without any asset.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FakeGatewayConfig.class})
class CopyGenerationTaskHandlerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeLlmGateway gateway;

    @Autowired
    private CopyGenerationTaskHandler handler;

    @Autowired
    private FactSheetService factSheetService;

    @Autowired
    private PolicyService policyService;

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
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from llm_call");
        jdbcTemplate.update("delete from store_policy");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        long callId = jdbcTemplate.queryForObject(
                "insert into llm_call (tenant_id, purpose, model, input_tokens, output_tokens, "
                        + "cost_usd, latency_ms, status) values (?, 'COPY', 'claude-opus-5-5', "
                        + "100, 50, 0.001400, 42, 'OK') returning id",
                Long.class, tenantId);
        gateway.setLlmCallId(callId);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, -5000, 'simple', 'MG-KTL17', 'Morgan 1.7L Kettle', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
        long userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'copy-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        CurrentUser user = new CurrentUser(userId, tenantId, Role.OPERATOR, "copy-op@example.test");
        FactsJson facts = new FactsJson("MG-KTL17", "Electric Kettles", "1.7 L", 350,
                "220-240V", "Stainless steel", "Silver", "1 year", List.of("Kettle", "Base"),
                List.of("Auto shut-off"), List.of(), List.of());
        factSheetService.saveDraft(user, productId, facts,
                Map.of("model", com.kiano.content.facts.FieldSource.P5));
        factVersion = factSheetService.lock(user, productId, 1,
                Set.of("model", "capacity", "powerW", "voltage", "warranty", "inBox")).version();
    }

    private static CopyDraft sampleDraft() {
        return new CopyDraft("Morgan MG-KTL17 Electric Kettle 1.7L - Boils fast",
                List.of("1.7 litre jar"), List.of("Boils water fast"),
                List.of(new CopyDraft.Faq("Does it auto shut off?", "Yes.")),
                "Morgan 1.7L Electric Kettle",
                "Morgan 1.7L Electric Kettle with auto shut-off",
                "Morgan 1.7L Electric Kettle 220-240V", "Hi! {{price}}.");
    }

    private TaskContext ctx(Map<String, Object> payload) {
        return new TaskContext(1L, tenantId, mapper.valueToTree(payload), 1);
    }

    @Test
    void generates6TextAssetsInReview_withFactVersionAndProvenance() throws Exception {
        gateway.queue(sampleDraft());

        Object out = handler.handle(ctx(Map.of("productId", productId, "factVersion",
                factVersion)));

        Map<?, ?> result = (Map<?, ?>) out;
        assertThat(result.get("factVersion")).isEqualTo(factVersion);
        List<AssetEntity> assets = assetMapper.selectList(null);
        assertThat(assets).hasSize(6);
        for (String spec : List.of("COPY_TITLE", "COPY_SHORT", "COPY_LONG", "COPY_SEO",
                "COPY_GSHOP", "COPY_WA")) {
            AssetEntity asset = assets.stream()
                    .filter(a -> spec.equals(a.getSpecCode())).findFirst().get();
            assertThat(asset.getKind()).isEqualTo("TEXT");
            assertThat(asset.getStatus()).isEqualTo("IN_REVIEW");
            assertThat(asset.getFactVersion()).isEqualTo(factVersion);
            assertThat(asset.getVariant()).isEqualTo("default");
            assertThat(asset.getContentJson()).isNotBlank();
            JsonNode provenance = mapper.readTree(asset.getProvenanceJson());
            assertThat(provenance.path("factVersion").asInt()).isEqualTo(factVersion);
            assertThat(provenance.path("model").asText()).isEqualTo(FakeLlmGateway.FAKE_MODEL);
            assertThat(provenance.path("llmCallId").asLong()).isPositive();
        }
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
    void onlySpec_savesSingleNewVersion() throws Exception {
        gateway.queue(sampleDraft());

        handler.handle(ctx(Map.of("productId", productId, "factVersion", factVersion,
                "onlySpec", "COPY_TITLE")));

        List<AssetEntity> assets = assetMapper.selectList(null);
        assertThat(assets).hasSize(1);
        assertThat(assets.get(0).getSpecCode()).isEqualTo("COPY_TITLE");
    }

    @Test
    void refusal_nonRetryable_noAssets() {
        gateway.failWith(new LlmRefusedException("I refuse", "unsafe"));

        assertThatThrownBy(() -> handler.handle(ctx(Map.of("productId", productId,
                "factVersion", factVersion))))
                .isInstanceOfSatisfying(NonRetryableTaskException.class,
                        ex -> assertThat(ex.getMessage()).startsWith("LLM_REFUSED"));
        assertThat(assetMapper.selectList(null)).isEmpty();
    }

    @Test
    void policyComplete_rendersIntoLongCopy() throws Exception {
        gateway.queue(sampleDraft());
        EnumMap<com.kiano.content.policy.PolicySection, SectionText> sections =
                new EnumMap<>(com.kiano.content.policy.PolicySection.class);
        for (com.kiano.content.policy.PolicySection section
                : com.kiano.content.policy.PolicySection.values()) {
            sections.put(section, new SectionText("T " + section, "B " + section, null));
        }
        long ownerId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'copy-owner@example.test', 'Owner', ?, 'OWNER') returning id",
                Long.class, tenantId, passwordEncoder.encode("owner-pass-123"));
        policyService.save(new CurrentUser(ownerId, tenantId, Role.OWNER, "copy-owner@example.test"),
                sections);

        handler.handle(ctx(Map.of("productId", productId, "factVersion", factVersion)));

        AssetEntity longCopy = assetMapper.selectList(null).stream()
                .filter(a -> "COPY_LONG".equals(a.getSpecCode())).findFirst().get();
        assertThat(longCopy.getTextBody()).doesNotContain("POLICY_PENDING");
        JsonNode content = mapper.readTree(longCopy.getContentJson());
        assertThat(content.path("policyVersion").asInt()).isEqualTo(1);
        assertThat(longCopy.getPrecheckJson()).doesNotContain("POLICY_PENDING");
    }
}