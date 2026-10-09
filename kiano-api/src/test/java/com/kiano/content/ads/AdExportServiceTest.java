package com.kiano.content.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.publish.PublicationEntity;
import com.kiano.content.publish.PublicationMapper;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ad export (Task 9): an export request records a PENDING AD_EXPORT
 * publication and an AD_EXPORT task; the handler zips the twelve approved
 * statics of every qualifying SKU with a manifest.csv, marks the publication
 * APPLIED with the exported asset ids, and skips or fails SKUs without them.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdExportServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdExportService service;

    @Autowired
    private AdExportTaskHandler handler;

    @Autowired
    private AdRenderService renderService;

    @Autowired
    private PublicationMapper publicationMapper;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper mapper;

    private long tenantId;
    private long storeId;
    private long userId;
    private CurrentUser user;
    private byte[] jpeg;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from platform_task");
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
        jdbcTemplate.update("delete from audit_log where tenant_id = ?", tenantId);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'adexport-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        user = new CurrentUser(userId, tenantId, Role.OPERATOR, "adexport-op@example.test");
        jpeg = ImageCodec.jpeg(square(), 0.9f);
    }

    @Test
    void happyPath_exportsZip_appliedPublicationWithAssets() throws Exception {
        long productId = product("MG-KTL17", true);
        seedFullStatics(productId, "MG-KTL17");

        long publicationId = service.request(user, List.of(productId));
        runHandler(publicationId);
        PublicationEntity publication = publicationMapper.selectById(publicationId);

        assertThat(publication.getStatus()).isEqualTo("APPLIED");
        JsonNode assetIds = mapper.readTree(publication.getAssetIds());
        assertThat(assetIds.size()).isEqualTo(12);
        JsonNode after = mapper.readTree(publication.getAfterJson());
        assertThat(after.path("fileCount").asInt()).isEqualTo(12);
        assertThat(after.path("skipped").isEmpty()).isTrue();
        String zipKey = after.path("zipKey").asText();
        assertThat(zipKey).isEqualTo("t" + tenantId + "/exports/ads/" + publicationId + ".zip");

        Map<String, byte[]> entries = readZip(storage.download(zipKey));
        assertThat(entries).containsKeys("MG-KTL17_pricehook_real_1080x1080_v1.jpg",
                "MG-KTL17_trust_mixed_1080x1920_v1.jpg", "manifest.csv");
        String manifest = new String(entries.get("manifest.csv"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(manifest).contains("file_name,sku,hook,size,headline,primary_text,"
                + "price_snapshot,asset_id");
        assertThat(manifest).contains("Hold it, price it"); // headline from AD_COPY
        assertThat(manifest).contains(",249.00,");           // pricehook snapshot
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'ADS_EXPORTED' and tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    void skuMissingVariant_producesSkippedNotMixedIntoZip() throws Exception {
        long productId = product("MG-KTL17", true);
        seedFullStatics(productId, "MG-KTL17");
        // drop one approved variant so the second SKU does not qualify
        long partial = product("MG-FAN18", true);
        seedStatics(partial, "MG-FAN18", true);

        long publicationId = service.request(user, List.of(productId, partial));
        runHandler(publicationId);
        PublicationEntity publication = publicationMapper.selectById(publicationId);

        JsonNode after = mapper.readTree(publication.getAfterJson());
        assertThat(after.path("fileCount").asInt()).isEqualTo(12);
        JsonNode skipped = after.path("skipped");
        assertThat(skipped.path("MG-FAN18").path("reason").asText())
                .isEqualTo("NOT_ALL_APPROVED");
        assertThat(skipped.path("MG-FAN18").path("missing").toString())
                .contains("pricehook-1080x1080");
        Map<String, byte[]> entries = readZip(storage.download(
                after.path("zipKey").asText()));
        assertThat(entries.keySet().stream()
                .filter(name -> name.startsWith("MG-FAN18"))).isEmpty();
    }

    @Test
    void noQualifyingSku_publicationFailsNothingToExport() {
        long productId = product("MG-KTL17", true); // HERO but no statics at all

        long publicationId = service.request(user, List.of(productId));

        assertThatThrownBy(() -> runHandler(publicationId))
                .isInstanceOf(NonRetryableTaskException.class)
                .hasMessageContaining("NOTHING_TO_EXPORT");
        PublicationEntity publication = publicationMapper.selectById(publicationId);
        assertThat(publication.getStatus()).isEqualTo("FAILED");
        assertThat(publication.getError()).contains("NOTHING_TO_EXPORT");
    }

    @Test
    void emptyProductList_exportsAllHeroProducts() {
        long full = product("MG-KTL17", true);
        seedFullStatics(full, "MG-KTL17");
        product("MG-NONHERO2", false); // STANDARD - never exported

        long publicationId = service.request(user, null);

        JsonNode payload = enqueuedPayload();
        assertThat(payload.path("productIds").toString()).contains(String.valueOf(full));

        runHandler(publicationId);
        PublicationEntity publication = publicationMapper.selectById(publicationId);
        assertThat(publication.getStatus()).isEqualTo("APPLIED");
    }

    @Test
    void noHeroProducts_requestFails_422() {
        product("MG-NONHERO2", false);

        assertThatThrownBy(() -> service.request(user, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("NOTHING_TO_EXPORT");
                });
    }

    @Test
    void downloadUri_andList_afterApply() {
        long productId = product("MG-KTL17", true);
        seedFullStatics(productId, "MG-KTL17");
        long publicationId = service.request(user, List.of(productId));
        runHandler(publicationId);

        var uri = service.downloadUri(tenantId, publicationId);
        assertThat(uri.toString()).startsWith("http");

        var views = service.list(tenantId);
        assertThat(views).singleElement().satisfies(view -> {
            assertThat(view.publicationId()).isEqualTo(publicationId);
            assertThat(view.status()).isEqualTo("APPLIED");
            assertThat(view.fileCount()).isEqualTo(12);
            assertThat(view.publishedAt()).isNotNull();
        });
    }

    // ---- helpers ----

    private void runHandler(long publicationId) {
        JsonNode payload = enqueuedPayload();
        handler.handle(new TaskContext(1L, tenantId, payload, 1));
    }

    private JsonNode enqueuedPayload() {
        List<String> payloads = jdbcTemplate.queryForList(
                "select payload from platform_task where type = 'AD_EXPORT' "
                        + "and tenant_id = ? order by id desc limit 1",
                String.class, tenantId);
        return mapper.readTree(payloads.get(0));
    }

    private long product(String sku, boolean hero) {
        long id = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, ?, 'simple', ?, ?, 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), sku, "Kettle " + sku);
        if (hero) {
            jdbcTemplate.update("insert into product_profile (product_id, tenant_id, content_tier) "
                    + "values (?, ?, 'HERO')", id, tenantId);
        }
        return id;
    }

    private void seedFullStatics(long productId, String sku) {
        seedStatics(productId, sku, false);
    }

    /** All twelve approved statics; {@code skipFirst} leaves pricehook-1080x1080 out. */
    private void seedStatics(long productId, String sku, boolean skipFirst) {
        Map<AdHook, Long> copyIds = new LinkedHashMap<>();
        for (AdHook hook : AdHook.values()) {
            copyIds.put(hook, adCopy(productId, hook));
        }
        for (AdRenderService.AdVariant variant : renderService.allVariants()) {
            if (skipFirst && variant.variant().equals("pricehook-1080x1080")) {
                continue;
            }
            boolean price = variant.hook() == AdHook.PRICEHOOK;
            String key = "t" + tenantId + "/assets/" + productId + "/AD_STATIC/" + variant.variant()
                    + "/v1.jpg";
            storage.put(key, jpeg, "image/jpeg");
            String type = variant.hook() == AdHook.PROBLEM || variant.hook() == AdHook.TRUST
                    ? "mixed" : "real";
            String fileName = (sku + "_" + variant.hook().wire() + "_" + type + "_"
                    + variant.size().label() + "_v1.jpg");
            String provenance = "{\"template\":{\"code\":\"AD_" + variant.hook().name()
                    + "\",\"version\":1},\"adCopyAssetId\":"
                    + copyIds.get(variant.hook()) + ",\"baseAssetId\":42}";
            jdbcTemplate.update("insert into asset (tenant_id, product_id, spec_code, variant, "
                    + "version, kind, object_key, thumb_object_key, width, height, status, "
                    + "precheck_json, provenance_json, depends_on_price, price_snapshot, file_name) "
                    + "values (?, ?, 'AD_STATIC', ?, 1, 'IMAGE', ?, ?, ?, ?, 'APPROVED', "
                    + "'{}'::jsonb, ?::jsonb, ?, ?, ?)",
                    tenantId, productId, variant.variant(), key, key + "_thumb.jpg",
                    variant.size().width(), variant.size().height(), provenance,
                    price, price ? BigDecimal.valueOf(249) : null, fileName);
        }
    }

    private long adCopy(long productId, AdHook hook) {
        String headline = switch (hook) {
            case PRICEHOOK -> "Hold it, price it";
            case PROBLEM -> "Solved in seconds";
            case DEMO -> "See it boil";
            default -> "Buy with confidence";
        };
        AdCopyText text = new AdCopyText("Overlay " + hook, headline,
                "Primary text " + hook);
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "text_body, content_json, status, precheck_json, provenance_json) "
                        + "values (?, ?, 'AD_COPY', ?, 1, 'TEXT', ?, ?::jsonb, 'APPROVED', "
                        + "'{}'::jsonb, '{}'::jsonb) returning id",
                Long.class, tenantId, productId, hook.wire(), text.toJson(),
                "{\"hook\":\"" + hook.wire() + "\"}");
    }

    private static Map<String, byte[]> readZip(byte[] zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private static BufferedImage square() {
        BufferedImage img = new BufferedImage(1080, 1080, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1080, 1080);
        g.setColor(new Color(180, 60, 40));
        g.fillRect(200, 200, 680, 680);
        g.dispose();
        return img;
    }
}