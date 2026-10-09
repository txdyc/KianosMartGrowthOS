package com.kiano.content.ads;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.commerce.ProductPriceChanged;
import com.kiano.content.asset.AssetStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Price-change re-render (Task 8): the latest version of every price-dependent
 * AD_STATIC variant becomes STALE (APPROVED/PUBLISHED - id feeds the
 * auto-approval check) or ARCHIVED (IN_REVIEW/DRAFT), and an AD_RENDER task is
 * enqueued with priceOnly and the autoApproveFrom map. Variants without any
 * price-dependent asset are left untouched - no task, no audit.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdPriceListenerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private ObjectMapper mapper;

    private long tenantId;
    private long storeId;
    private long productId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from platform_task where tenant_id = "
                + "(select id from tenant where slug = 'kianosmart')");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update("delete from audit_log where tenant_id = ?", tenantId);
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, -6001, 'simple', 'MG-KTL17', 'Kettle 1.7L', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
    }

    @Test
    void approvedVariants_staled_andTaskEnqueuedWithAutoApproveFrom() {
        Map<String, Long> ids = new java.util.LinkedHashMap<>();
        ids.put("pricehook-1080x1080", priceAsset("pricehook-1080x1080", 1,
                AssetStatus.APPROVED.name()));
        ids.put("pricehook-1080x1350", priceAsset("pricehook-1080x1350", 1,
                AssetStatus.APPROVED.name()));
        ids.put("pricehook-1080x1920", priceAsset("pricehook-1080x1920", 1,
                AssetStatus.APPROVED.name()));
        publishPriceChange();

        for (Map.Entry<String, Long> entry : ids.entrySet()) {
            assertThat(status(entry.getValue())).isEqualTo(AssetStatus.STALE.name());
        }
        JsonNode task = latestRenderTask();
        assertThat(task.path("priceOnly").asBoolean()).isTrue();
        assertThat(task.path("variants").toString()).contains("pricehook-1080x1080",
                "pricehook-1080x1350", "pricehook-1080x1920");
        for (Map.Entry<String, Long> entry : ids.entrySet()) {
            assertThat(task.path("autoApproveFrom").path(entry.getKey()).asLong())
                    .isEqualTo(entry.getValue());
        }
        assertThat(audit("ADS_STALE_BY_PRICE")).isEqualTo(1);
    }

    @Test
    void inReviewVariants_archived_andTaskWithoutAutoApproveFrom() {
        long a = priceAsset("pricehook-1080x1080", 1, AssetStatus.IN_REVIEW.name());
        long b = priceAsset("pricehook-1080x1350", 1, AssetStatus.DRAFT.name());
        publishPriceChange();

        assertThat(status(a)).isEqualTo(AssetStatus.ARCHIVED.name());
        assertThat(status(b)).isEqualTo(AssetStatus.ARCHIVED.name());
        JsonNode task = latestRenderTask();
        assertThat(task.path("priceOnly").asBoolean()).isTrue();
        assertThat(task.has("autoApproveFrom")).isFalse();
        assertThat(task.path("variants").toString()).contains("pricehook-1080x1080");
    }

    @Test
    void noPriceDependentAdStatic_noTaskNoAudit() {
        // only a non-price hook has an asset - it is not picked up
        adStatic("problem-1080x1080", 1, AssetStatus.APPROVED.name(), false);
        publishPriceChange();

        assertThat(latestRenderTask()).isNull();
        assertThat(audit("ADS_STALE_BY_PRICE")).isZero();
    }

    @Test
    void publishedVariant_staled_withAutoApproveFrom() {
        long a = priceAsset("pricehook-1080x1080", 1, AssetStatus.PUBLISHED.name());
        publishPriceChange();

        assertThat(status(a)).isEqualTo(AssetStatus.STALE.name());
        JsonNode task = latestRenderTask();
        assertThat(task.path("autoApproveFrom").path("pricehook-1080x1080").asLong())
                .isEqualTo(a);
    }

    @Test
    void mixedStatuses_handledPerVariant() {
        long approved = priceAsset("pricehook-1080x1080", 1, AssetStatus.APPROVED.name());
        long inReview = priceAsset("pricehook-1080x1350", 1, AssetStatus.IN_REVIEW.name());
        publishPriceChange();

        assertThat(status(approved)).isEqualTo(AssetStatus.STALE.name());
        assertThat(status(inReview)).isEqualTo(AssetStatus.ARCHIVED.name());
        JsonNode task = latestRenderTask();
        // both variants re-render; only the approved one feeds the auto-approval
        assertThat(task.path("variants").toString()).contains("pricehook-1080x1080",
                "pricehook-1080x1350");
        assertThat(task.path("autoApproveFrom").path("pricehook-1080x1080").asLong())
                .isEqualTo(approved);
        assertThat(task.path("autoApproveFrom").has("pricehook-1080x1350")).isFalse();
    }

    @Test
    void alreadyStaleVariant_isRerenderedWithAutoApproveFrom() {
        long a = priceAsset("pricehook-1080x1080", 1, AssetStatus.STALE.name());
        publishPriceChange();

        assertThat(status(a)).isEqualTo(AssetStatus.STALE.name());
        assertThat(latestRenderTask().path("autoApproveFrom")
                .path("pricehook-1080x1080").asLong()).isEqualTo(a);
    }

    @Test
    void onlyLatestVersionPerVariant_isMarked() {
        long v1 = priceAsset("pricehook-1080x1080", 1, AssetStatus.APPROVED.name());
        long v2 = priceAsset("pricehook-1080x1080", 2, AssetStatus.REJECTED.name());
        publishPriceChange();

        // the latest (REJECTED) version is left as-is; v1 stays APPROVED
        assertThat(status(v2)).isEqualTo(AssetStatus.REJECTED.name());
        assertThat(status(v1)).isEqualTo(AssetStatus.APPROVED.name());
        // the variant is still re-rendered without an auto-approval target
        JsonNode task = latestRenderTask();
        assertThat(task.path("variants").toString()).contains("pricehook-1080x1080");
        assertThat(task.has("autoApproveFrom")).isFalse();
    }

    // ---- helpers ----

    private void publishPriceChange() {
        // AFTER_COMMIT listeners only run when the publish happens in a transaction
        new TransactionTemplate(txManager).executeWithoutResult(status -> events.publishEvent(
                new ProductPriceChanged(tenantId, productId, "MG-KTL17",
                        BigDecimal.valueOf(299), BigDecimal.valueOf(249),
                        BigDecimal.valueOf(299), BigDecimal.valueOf(249),
                        null, null)));
    }

    private long priceAsset(String variant, int version, String status) {
        return adStatic(variant, version, status, true);
    }

    private long adStatic(String variant, int version, String status, boolean dependsOnPrice) {
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json, depends_on_price, price_snapshot) "
                        + "values (?, ?, 'AD_STATIC', ?, ?, 'IMAGE', ?, '{}'::jsonb, ?::jsonb, "
                        + "?, ?) returning id",
                Long.class, tenantId, productId, variant, version, status,
                "{\"template\":{\"code\":\"AD_PRICEHOOK\",\"version\":1},"
                        + "\"adCopyAssetId\":7,\"baseAssetId\":8}",
                dependsOnPrice, dependsOnPrice ? BigDecimal.valueOf(299) : null);
    }

    private String status(long assetId) {
        return jdbcTemplate.queryForObject("select status from asset where id = ?", String.class,
                assetId);
    }

    private JsonNode latestRenderTask() {
        List<String> payloads = jdbcTemplate.queryForList(
                "select payload from platform_task where tenant_id = ? and type = 'AD_RENDER' "
                        + "order by id desc limit 1",
                String.class, tenantId);
        return payloads.isEmpty() ? null : mapper.readTree(payloads.get(0));
    }

    private int audit(String action) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = ? and tenant_id = ?", Integer.class,
                action, tenantId);
    }
}