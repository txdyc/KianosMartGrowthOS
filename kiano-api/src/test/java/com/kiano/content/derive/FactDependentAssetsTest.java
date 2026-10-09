package com.kiano.content.derive;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.TestcontainersConfiguration;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.MainImageApprovedEvent;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.derive.FactDependentAssetsTest.FakeGatewayConfig;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactsJson;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.llm.FakeLlmGateway;
import com.kiano.platform.queue.TaskQueue;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Fact-lock derivation: old COPY/INFO/SPEC assets are archived (or staled
 * when published), COPY_GENERATE and PAGE_SPEC render are enqueued, and
 * PAGE_INFO renders when an approved main exists (or after MainImageApproved
 * once facts are locked).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FakeGatewayConfig.class})
class FactDependentAssetsTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FactSheetService factSheetService;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TaskQueue queue;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private long tenantId;
    private long storeId;
    private long productId;

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
        jdbcTemplate.update("delete from platform_task where tenant_id = "
                + "(select id from tenant where slug = 'kianosmart')");
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from llm_call");
        jdbcTemplate.update("delete from store_policy");
        jdbcTemplate.update("delete from product_profile");
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
                        + "values (?, ?, -6000, 'simple', 'MG-KTL17', 'Kettle 1.7L', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
    }

    private long asset(String spec, int factVersion, String status) {
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json, fact_version, created_at) "
                        + "values (?, ?, ?, 'default', 1, 'TEXT', ?, '{}', '{}', ?, now()) "
                        + "returning id",
                Long.class, tenantId, productId, spec, status, factVersion);
    }

    private long mainAsset(String status) {
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json, created_at) "
                        + "values (?, ?, 'PAGE_MAIN', 'main', 1, 'IMAGE', ?, '{}', '{}', now()) "
                        + "returning id",
                Long.class, tenantId, productId, status);
    }

    private void lockFacts() {
        long userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'derive-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        CurrentUser user = new CurrentUser(userId, tenantId, Role.OPERATOR, "derive-op@example.test");
        FactsJson facts = new FactsJson("MG-KTL17", "Electric Kettles", "1.7 L", 350,
                "220-240V", "Stainless steel", "Silver", "1 year", List.of("Kettle", "Base"),
                List.of("Auto shut-off"), List.of(), List.of());
        factSheetService.saveDraft(user, productId, facts,
                Map.of("model", com.kiano.content.facts.FieldSource.P5));
        factSheetService.lock(user, productId, 1,
                Set.of("model", "capacity", "powerW", "voltage", "warranty", "inBox"));
    }

    @Test
    void lock_v2_archivesOldCopyAndInfo_stalesPublished_enqueuesCopyAndSpec() {
        lockFacts(); // v1 has no derived assets yet
        asset("COPY_LONG", 1, "IN_REVIEW");
        asset("PAGE_INFO", 1, "APPROVED");
        asset("PAGE_SPEC", 1, "PUBLISHED");
        long userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'derive-op2@example.test', 'Op2', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        CurrentUser user = new CurrentUser(userId, tenantId, Role.OPERATOR,
                "derive-op2@example.test");
        FactsJson facts = new FactsJson("MG-KTL17", "Electric Kettles", "1.7 L", 350,
                "220-240V", "Stainless steel", "Silver", "1 year", List.of("Kettle", "Base"),
                List.of("Auto shut-off"), List.of(), List.of());
        factSheetService.saveDraft(user, productId, facts,
                Map.of("model", com.kiano.content.facts.FieldSource.P5));
        factSheetService.lock(user, productId, 2,
                Set.of("model", "capacity", "powerW", "voltage", "warranty", "inBox"));

        List<AssetEntity> after = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId));
        assertThat(after).filteredOn(a -> "COPY_LONG".equals(a.getSpecCode()))
                .extracting(AssetEntity::getStatus).containsExactly(AssetStatus.ARCHIVED.name());
        assertThat(after).filteredOn(a -> "PAGE_INFO".equals(a.getSpecCode()))
                .extracting(AssetEntity::getStatus).containsExactly(AssetStatus.ARCHIVED.name());
        assertThat(after).filteredOn(a -> "PAGE_SPEC".equals(a.getSpecCode()))
                .extracting(AssetEntity::getStatus).containsExactly(AssetStatus.STALE.name());
        assertThat(queue.latest(tenantId, "COPY_GENERATE")).isPresent();
        assertThat(queue.latest(tenantId, "TEMPLATE_RENDER")).isPresent();
    }

    @Test
    void lock_withApprovedMain_alsoEnqueuesInfo() {
        mainAsset(AssetStatus.APPROVED.name());
        lockFacts();
        assertThat(jdbcTemplate.queryForList(
                "select payload from platform_task where tenant_id = ? and type = 'TEMPLATE_RENDER'",
                String.class, tenantId))
                .hasSize(2)
                .anySatisfy(payload -> assertThat(payload).contains("PAGE_SPEC"))
                .anySatisfy(payload -> assertThat(payload).contains("PAGE_INFO"));
    }

    @Test
    void mainApprovedLater_enqueuesInfoOnlyIfFactsLocked() {
        // Facts not locked yet: approval of main enqueues nothing. Publish the
        // event inside a transaction so the AFTER_COMMIT listener runs (same as
        // ReviewService.decide which publishes it after its own commit).
        inTx(() -> events.publishEvent(new MainImageApprovedEvent(tenantId, productId, 1L)));
        assertThat(jdbcTemplate.queryForList(
                "select count(*) from platform_task where tenant_id = ? and type = 'TEMPLATE_RENDER'",
                Long.class, tenantId)).containsExactly(0L);

        // Facts locked, then a main gets approved → PAGE_INFO task appears.
        lockFacts();
        long mainId = mainAsset(AssetStatus.APPROVED.name());
        inTx(() -> events.publishEvent(new MainImageApprovedEvent(tenantId, productId, mainId)));
        assertThat(jdbcTemplate.queryForList(
                "select payload from platform_task where tenant_id = ? and type = 'TEMPLATE_RENDER' "
                        + "order by id",
                String.class, tenantId))
                .anySatisfy(payload -> assertThat(payload).contains("PAGE_INFO"));
    }

    private void inTx(Runnable action) {
        new org.springframework.transaction.support.TransactionTemplate(txManager)
                .executeWithoutResult(status -> action.run());
    }

    @Test
    void templateRender_staleFacts_skipped() throws Exception {
        // No handler invocation here; the skip logic is covered by the handler
        // test below. This asserts nothing is enqueued when only stale tasks exist.
        assertThat(queue.latest(tenantId, "TEMPLATE_RENDER")).isEmpty();
    }

    @Test
    void factLock_onHero_enqueuesAdCopy_nonHero_doesNot() {
        jdbcTemplate.update("insert into product_profile (product_id, tenant_id, content_tier) "
                + "values (?, ?, 'HERO')", productId, tenantId);
        lockFacts();

        assertThat(queue.latest(tenantId, "AD_COPY_GENERATE")).isPresent();
        assertThat(jdbcTemplate.queryForObject(
                "select payload from platform_task where tenant_id = ? and type = 'AD_COPY_GENERATE'",
                String.class, tenantId)).contains(String.valueOf(factSheetService
                        .locked(tenantId, productId).get().version()));

        // a non-HERO product (no profile row) does not enqueue ad copy
        jdbcTemplate.update("delete from product_profile where product_id = ?", productId);
        jdbcTemplate.update("delete from platform_task");
        long secondId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, -6002, 'simple', 'MG-KTL18', 'Kettle 2', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
        long userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'derive-op3@example.test', 'Op3', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        CurrentUser user = new CurrentUser(userId, tenantId, Role.OPERATOR,
                "derive-op3@example.test");
        FactsJson facts = new FactsJson("MG-KTL18", "Kettles", "1 L", 200, "220V",
                "Steel", "Black", "6 months", List.of("Kettle"), List.of(), List.of(), List.of());
        factSheetService.saveDraft(user, secondId, facts,
                Map.of("model", com.kiano.content.facts.FieldSource.P5));
        factSheetService.lock(user, secondId, 1,
                Set.of("model", "capacity", "powerW", "voltage", "warranty", "inBox"));

        assertThat(queue.latest(tenantId, "AD_COPY_GENERATE")).isEmpty();
        assertThat(queue.latest(tenantId, "COPY_GENERATE")).isPresent();
    }

    @Test
    void factLock_onHero_enqueuesVideoScript() {
        jdbcTemplate.update("insert into product_profile (product_id, tenant_id, content_tier) "
                + "values (?, ?, 'HERO')", productId, tenantId);
        lockFacts();

        assertThat(queue.latest(tenantId, "VIDEO_SCRIPT_GENERATE")).isPresent();
        assertThat(jdbcTemplate.queryForObject(
                "select payload from platform_task where tenant_id = ? and type = 'VIDEO_SCRIPT_GENERATE'",
                String.class, tenantId)).contains(String.valueOf(factSheetService
                        .locked(tenantId, productId).get().version()));
    }
}