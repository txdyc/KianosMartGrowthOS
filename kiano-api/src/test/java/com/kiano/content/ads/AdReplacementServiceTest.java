package com.kiano.content.ads;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.ads.AdReplacementService.Replacement;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Replacement list (Task 9): only statics that were shipped in an APPLIED
 * ad export and have since gone STALE are listed, with the same variant's
 * latest APPROVED version as the replacement.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdReplacementServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdReplacementService service;

    private long tenantId;
    private long storeId;
    private long productId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from publication");
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
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
                        + "values (?, ?, -6001, 'simple', 'MG-KTL17', 'Kettle 1.7L', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
    }

    @Test
    void exportedAndNowStale_listsReplacement() {
        long oldId = staticAsset("pricehook-1080x1080", 1, "STALE", "MG-KTL17_old.jpg");
        long newId = staticAsset("pricehook-1080x1080", 2, "APPROVED", "MG-KTL17_new.jpg");
        exportedPublication(List.of(oldId), "APPLIED");

        List<Replacement> replacements = service.list(tenantId);

        assertThat(replacements).singleElement().satisfies(replacement -> {
            assertThat(replacement.sku()).isEqualTo("MG-KTL17");
            assertThat(replacement.variant()).isEqualTo("pricehook-1080x1080");
            assertThat(replacement.oldFileName()).isEqualTo("MG-KTL17_old.jpg");
            assertThat(replacement.oldAssetId()).isEqualTo(oldId);
            assertThat(replacement.newFileName()).isEqualTo("MG-KTL17_new.jpg");
            assertThat(replacement.newAssetId()).isEqualTo(newId);
            assertThat(replacement.newStatus()).isEqualTo("APPROVED");
        });
    }

    @Test
    void exportedButStillApproved_notListed() {
        long oldId = staticAsset("pricehook-1080x1080", 1, "APPROVED", "MG-KTL17_ok.jpg");
        exportedPublication(List.of(oldId), "APPLIED");

        assertThat(service.list(tenantId)).isEmpty();
    }

    @Test
    void staleButNeverExported_notListed() {
        long oldId = staticAsset("pricehook-1080x1080", 1, "STALE", "MG-KTL17_stale.jpg");
        staticAsset("pricehook-1080x1080", 2, "APPROVED", "MG-KTL17_new.jpg");

        assertThat(service.list(tenantId)).isEmpty();
    }

    @Test
    void staleWithoutReplacementYet_newStatusNull() {
        long oldId = staticAsset("pricehook-1080x1080", 1, "STALE", "MG-KTL17_old.jpg");
        exportedPublication(List.of(oldId), "APPLIED");

        List<Replacement> replacements = service.list(tenantId);

        assertThat(replacements).singleElement().satisfies(replacement -> {
            assertThat(replacement.newStatus()).isNull();
            assertThat(replacement.newAssetId()).isNull();
        });
    }

    @Test
    void pendingExport_ignored() {
        long oldId = staticAsset("pricehook-1080x1080", 1, "STALE", "MG-KTL17_old.jpg");
        exportedPublication(List.of(oldId), "PENDING");

        assertThat(service.list(tenantId)).isEmpty();
    }

    // ---- helpers ----

    private long staticAsset(String variant, int version, String status, String fileName) {
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json, depends_on_price, "
                        + "price_snapshot, file_name) "
                        + "values (?, ?, 'AD_STATIC', ?, ?, 'IMAGE', ?, '{}'::jsonb, ?::jsonb, true, "
                        + "?, ?) returning id",
                Long.class, tenantId, productId, variant, version, status,
                "{\"template\":{\"code\":\"AD_PRICEHOOK\",\"version\":1}}",
                BigDecimal.valueOf(299), fileName);
    }

    private void exportedPublication(List<Long> assetIds, String status) {
        String ids = "[" + String.join(",",
                assetIds.stream().map(String::valueOf).toList()) + "]";
        jdbcTemplate.update(
                "insert into publication (tenant_id, product_id, environment, target, asset_ids, "
                        + "uploaded_media_ids, archived_asset_ids, status) "
                        + "values (?, ?, 'PRODUCTION', 'AD_EXPORT', ?::jsonb, '[]'::jsonb, '[]'::jsonb, ?)",
                tenantId, productId, ids, status);
    }
}