package com.kiano.content.copy;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.copy.PolicyRerenderTest.FakeGatewayConfig;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactsJson;
import com.kiano.content.policy.PolicyService;
import com.kiano.content.policy.PolicyService.SectionText;
import com.kiano.content.publish.PublicationService;
import com.kiano.content.publish.PublicationService.NeedsRepublishItem;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.llm.FakeLlmGateway;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Policy change re-render (Task 12): a policy save queues POLICY_RERENDER,
 * the handler re-renders COPY_LONG from content_json without any LLM call,
 * auto-approves when the old version was APPROVED/PUBLISHED and both templates
 * are approved, keeps IN_REVIEW as IN_REVIEW, turns a PUBLISHED old version
 * into STALE and lists the product in needs-republish.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FakeGatewayConfig.class})
class PolicyRerenderTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FakeLlmGateway gateway;

    @Autowired
    private PolicyRerender rerender;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private FactSheetService factSheetService;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private PublicationService publicationService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper mapper;

    private long tenantId;
    private long storeId;
    private long productId;
    private int factVersion;
    private CurrentUser owner;

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
        jdbcTemplate.update("delete from platform_task where tenant_id = "
                + "(select id from tenant where slug = 'kianosmart')");
        jdbcTemplate.update("delete from asset_review");
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
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, -8000, 'simple', 'MG-KTL17', 'Morgan 1.7L Kettle', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
        long opId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'policy-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        CurrentUser op = new CurrentUser(opId, tenantId, Role.OPERATOR, "policy-op@example.test");
        FactsJson facts = new FactsJson("MG-KTL17", "Electric Kettles", "1.7 L", 350,
                "220-240V", "Stainless steel", "Silver", "1 year", List.of("Kettle", "Base"),
                List.of("Auto shut-off"), List.of(), List.of());
        factSheetService.saveDraft(op, productId, facts,
                Map.of("model", com.kiano.content.facts.FieldSource.P5));
        factVersion = factSheetService.lock(op, productId, 1,
                Set.of("model", "capacity", "powerW", "voltage", "warranty", "inBox")).version();
        long ownerId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'policy-owner@example.test', 'Owner', ?, 'OWNER') returning id",
                Long.class, tenantId, passwordEncoder.encode("owner-pass-123"));
        owner = new CurrentUser(ownerId, tenantId, Role.OWNER, "policy-owner@example.test");
    }

    private void savePolicy(int version) {
        EnumMap<com.kiano.content.policy.PolicySection, SectionText> sections =
                new EnumMap<>(com.kiano.content.policy.PolicySection.class);
        for (com.kiano.content.policy.PolicySection section
                : com.kiano.content.policy.PolicySection.values()) {
            sections.put(section, new SectionText("T " + section, "B " + section + " v"
                    + version));
        }
        policyService.save(owner, sections);
    }

    /** A COPY_LONG with the given status and policyVersion in its content_json. */
    private long copyLong(String status, int policyVersion) {
        String contentJson = mapper.writeValueAsString(Map.of(
                "copy", Map.<String, Object>of(
                        "title", "Morgan Kettle",
                        "shortBullets", List.of("1.7 litre jar"),
                        "whyBuy", List.of("Boils fast"),
                        "faq", List.of(Map.of("q", "Auto shut off?", "a", "Yes.")),
                        "seoTitle", "Morgan 1.7L Kettle",
                        "seoDescription", "Morgan kettle",
                        "gshopTitle", "Morgan Kettle 220-240V",
                        "waMessage", "Hi {{price}}."),
                "factVersion", factVersion,
                "policyVersion", policyVersion));
        String body = "<h2>Why buy</h2><p>Boils fast</p>"
                + "<div class=\"kiano-policy-slot\"></div>";
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "text_body, content_json, status, precheck_json, provenance_json, "
                        + "fact_version, created_at) values (?, ?, 'COPY_LONG', 'default', 1, "
                        + "'TEXT', ?, ?, ?, '{}', '{}', ?, now()) returning id",
                Long.class, tenantId, productId, body, contentJson, status, factVersion);
    }

    private void runRerender(int policyVersion) throws Exception {
        rerender.handle(new TaskContext(1L, tenantId,
                mapper.createObjectNode().put("version", policyVersion), 1));
    }

    private List<AssetEntity> copyLongs() {
        return assetMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "COPY_LONG")
                .orderByAsc(AssetEntity::getVersion));
    }

    /** Policy v1, copy rendered against it, then policy v2 saved + re-render. */
    private void policyV1ThenV2(String status) throws Exception {
        savePolicy(1);
        copyLong(status, 1);
        savePolicy(2);
        runRerender(2);
    }

    @Test
    void policyChange_rerendersLongCopyWithoutLlm() throws Exception {
        policyV1ThenV2("IN_REVIEW");

        assertThat(gateway.callCount()).isZero();
        List<AssetEntity> assets = copyLongs();
        assertThat(assets).hasSize(2);
        AssetEntity newest = assets.get(assets.size() - 1);
        assertThat(newest.getStatus()).isEqualTo(AssetStatus.IN_REVIEW.name());
        // the POLICY_BLOCK is spliced into the slot (section title escaped)
        assertThat(newest.getTextBody()).contains("T DELIVERY")
                .contains("B DELIVERY v2")
                .doesNotContain("kiano-policy-slot")
                .doesNotContain("POLICY_PENDING");
        assertThat(mapper.readTree(newest.getContentJson()).path("policyVersion").asInt())
                .isEqualTo(2);
    }

    @Test
    void approvedOrPublished_autoApprovedNewVersion_audited() throws Exception {
        policyV1ThenV2("APPROVED");

        List<AssetEntity> assets = copyLongs();
        assertThat(assets).hasSize(2);
        assertThat(assets.get(0).getStatus()).isEqualTo(AssetStatus.ARCHIVED.name());
        assertThat(assets.get(1).getStatus()).isEqualTo(AssetStatus.APPROVED.name());
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where tenant_id = ? and action = "
                        + "'COPY_AUTO_APPROVED_POLICY_CHANGE'",
                Long.class, tenantId);
        assertThat(count).isPositive();
        assertThat(jdbcTemplate.queryForObject(
                "select actor_type from audit_log where tenant_id = ? and action = "
                        + "'COPY_AUTO_APPROVED_POLICY_CHANGE' order by id desc limit 1",
                String.class, tenantId)).isEqualTo("SYSTEM");
    }

    @Test
    void published_becomesStale_listedInNeedsRepublish() throws Exception {
        policyV1ThenV2("PUBLISHED");

        List<AssetEntity> assets = copyLongs();
        assertThat(assets).hasSize(2);
        assertThat(assets.get(0).getStatus()).isEqualTo(AssetStatus.STALE.name());
        assertThat(assets.get(1).getStatus()).isEqualTo(AssetStatus.APPROVED.name());

        List<NeedsRepublishItem> list = publicationService.needsRepublish(tenantId);
        assertThat(list).extracting(NeedsRepublishItem::productId).contains(productId);
        assertThat(list).flatExtracting(NeedsRepublishItem::reasons)
                .contains("POLICY_CHANGED");
    }

    @Test
    void inReview_staysInReview() throws Exception {
        policyV1ThenV2("IN_REVIEW");

        List<AssetEntity> assets = copyLongs();
        assertThat(assets).hasSize(2);
        assertThat(assets.get(0).getStatus()).isEqualTo(AssetStatus.ARCHIVED.name());
        assertThat(assets.get(1).getStatus()).isEqualTo(AssetStatus.IN_REVIEW.name());
        assertThat(gateway.callCount()).isZero();
    }
}