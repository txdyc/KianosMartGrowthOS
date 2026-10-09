package com.kiano.content.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.ads.AdCopyText;
import com.kiano.content.ads.FrameExtractor;
import com.kiano.content.asset.ReviewService.Decision;
import com.kiano.content.asset.ReviewService.DecisionResult;
import com.kiano.content.asset.ReviewService.ReviewItem;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactsJson;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Review board integration with the ad assets (Task 10): AD_STATIC sorts after
 * the page images and before the copy, AD_COPY after all copy specs; AD_
 * REGENERATE enqueues the ad copy/ad render tasks (demo advances the frame);
 * and editing an AD_COPY validates and prechecks the JSON body.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdReviewTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReviewService service;

    @Autowired
    private FactSheetService factSheetService;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private FrameExtractor frameExtractor;

    private long tenantId;
    private long storeId;
    private long productId;
    private long userId;
    private CurrentUser user;
    private int factVersion;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from platform_task where tenant_id = "
                + "(select id from tenant where slug = 'kianosmart')");
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from product_profile");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update("delete from audit_log where tenant_id = ?", tenantId);
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store_policy");
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
        userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'adreview-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        user = new CurrentUser(userId, tenantId, Role.OPERATOR, "adreview-op@example.test");
        FactsJson facts = new FactsJson("MG-KTL17", "Kettles", "1.7 L", 350, "220-240V",
                "Steel", "Silver", "1 year", List.of("Kettle"), List.of(), List.of(), List.of());
        factSheetService.saveDraft(user, productId, facts,
                Map.of("model", com.kiano.content.facts.FieldSource.P5));
        factVersion = factSheetService.lock(user, productId, 1,
                Set.of("model", "capacity", "powerW", "voltage", "warranty", "inBox")).version();
    }

    @Test
    void list_sortsAdStaticsBetweenPagesAndText_andAdCopyAfterText() {
        long pageMain = asset("PAGE_MAIN", "main", "IMAGE", "IN_REVIEW", "{}", null);
        long staticSquare = asset("AD_STATIC", "pricehook-1080x1080", "IMAGE", "IN_REVIEW",
                "{}", null);
        long staticVertical = asset("AD_STATIC", "pricehook-1080x1920", "IMAGE", "IN_REVIEW",
                "{}", null);
        long staticDemo = asset("AD_STATIC", "demo-1080x1080", "IMAGE", "IN_REVIEW", "{}", null);
        long staticTrust = asset("AD_STATIC", "trust-1080x1080", "IMAGE", "IN_REVIEW", "{}", null);
        long copyTitle = asset("COPY_TITLE", "default", "TEXT", "IN_REVIEW", "{}", "Title");
        long adCopy = asset("AD_COPY", "pricehook", "TEXT", "IN_REVIEW", "{}", null);

        List<ReviewItem> items = service.list(user, null, "IN_REVIEW", null);

        assertThat(items).extracting(ReviewItem::assetId).containsExactly(
                pageMain, staticSquare, staticVertical, staticDemo, staticTrust,
                copyTitle, adCopy);
    }

    @Test
    void regenerateAdCopy_enqueuesOnlyHookAndArchives() {
        long adCopy = asset("AD_COPY", "pricehook", "TEXT", "IN_REVIEW", "{}", null);
        jdbcTemplate.update("update asset set fact_version = ? where id = ?", factVersion, adCopy);

        DecisionResult result = service.decide(user, adCopy, Decision.REGENERATE, List.of(), null);

        assertThat(result.runId()).isNull();
        assertThat(status(adCopy)).isEqualTo("ARCHIVED");
        JsonNode payload = taskPayload("AD_COPY_GENERATE");
        assertThat(payload.path("onlyHook").asText()).isEqualTo("pricehook");
        assertThat(payload.path("productId").asLong()).isEqualTo(productId);
        assertThat(payload.path("factVersion").asInt()).isEqualTo(factVersion);
    }

    @Test
    void regenerateAdStatic_enqueuesRenderWithoutFrame() {
        long staticAsset = asset("AD_STATIC", "pricehook-1080x1080", "IMAGE", "IN_REVIEW",
                "{\"template\":{\"code\":\"AD_PRICEHOOK\",\"version\":1}}", null);

        service.decide(user, staticAsset, Decision.REGENERATE, List.of(), null);

        JsonNode payload = taskPayload("AD_RENDER");
        assertThat(payload.path("variants").toString()).contains("pricehook-1080x1080");
        assertThat(payload.has("frameCandidate")).isFalse();
        assertThat(status(staticAsset)).isEqualTo("ARCHIVED");
    }

    @Test
    void regenerateDemoStatic_advancesFrameCandidateFromProvenance_withoutVideoWork() {
        long video = videoAsset();
        long demo = asset("AD_STATIC", "demo-1080x1080", "IMAGE", "IN_REVIEW",
                "{\"template\":{\"code\":\"AD_DEMO\",\"version\":1},"
                        + "\"sourceMediaId\":" + video + ",\"frameTime\":3.0,"
                        + "\"frameCandidate\":1}", null);

        service.decide(user, demo, Decision.REGENERATE, List.of(), null);

        assertThat(taskPayload("AD_RENDER").path("frameCandidate").asInt()).isEqualTo(2);
        // no V1 download or ffmpeg inside the review transaction
        verifyNoInteractions(frameExtractor);
    }

    @Test
    void regenerateLegacyDemoStatic_withoutRecordedCandidate_usesSecondFrame() {
        long video = videoAsset();
        long demo = asset("AD_STATIC", "demo-1080x1080", "IMAGE", "IN_REVIEW",
                "{\"template\":{\"code\":\"AD_DEMO\",\"version\":1},"
                        + "\"sourceMediaId\":" + video + ",\"frameTime\":1.5}", null);

        service.decide(user, demo, Decision.REGENERATE, List.of(), null);

        assertThat(taskPayload("AD_RENDER").path("frameCandidate").asInt()).isEqualTo(1);
        verifyNoInteractions(frameExtractor);
    }

    @Test
    void editAdCopy_invalidJson_422() {
        long adCopy = asset("AD_COPY", "pricehook", "TEXT", "IN_REVIEW",
                "{}", new AdCopyText("a", "b", "c").toJson());

        assertThatThrownBy(() -> service.editText(user, adCopy, "not json"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("AD_COPY_INVALID");
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                });
    }

    @Test
    void editAdCopy_validBody_precheckedNewVersion() {
        long adCopy = asset("AD_COPY", "pricehook", "TEXT", "IN_REVIEW", "{}",
                new AdCopyText("a", "b", "c").toJson());
        jdbcTemplate.update("update asset set fact_version = ? where id = ?", factVersion, adCopy);

        ReviewItem edited = service.editText(user, adCopy,
                new AdCopyText("a", "b", "Pay only GH₵ 199").toJson());

        assertThat(edited.specCode()).isEqualTo("AD_COPY");
        assertThat(edited.version()).isEqualTo(2);
        assertThat(edited.flags()).contains(PrecheckFlag.PRICE_IN_COPY);
        // the edit opened a new IN_REVIEW row and archived the superseded draft version
        assertThat(status(adCopy)).isEqualTo("ARCHIVED");
    }

    // ---- helpers ----

    private long videoAsset() {
        byte[] jpeg = ImageCodec.jpeg(square(), 0.9f);
        String key = "t" + tenantId + "/source-media/" + productId + "/V1.bin";
        storage.put(key, jpeg, "application/octet-stream");
        return jdbcTemplate.queryForObject(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, thumb_object_key, content_type, size_bytes, sha256, qc_json, status, duration_s) "
                        + "values (?, ?, 'V1', 'VIDEO', 'demo.mp4', ?, null, 'video/mp4', 1024, "
                        + "?, '{}', 'ACCEPTED', 12.0) returning id",
                Long.class, tenantId, productId, key, "0".repeat(64));
    }

    private long asset(String specCode, String variant, String kind, String status,
            String provenanceJson, String textBody) {
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json, text_body) "
                        + "values (?, ?, ?, ?, 1, ?, ?, '{}'::jsonb, ?::jsonb, ?) returning id",
                Long.class, tenantId, productId, specCode, variant, kind, status,
                provenanceJson, textBody);
    }

    private String status(long assetId) {
        return jdbcTemplate.queryForObject("select status from asset where id = ?", String.class,
                assetId);
    }

    private JsonNode taskPayload(String type) {
        List<String> payloads = jdbcTemplate.queryForList(
                "select payload from platform_task where tenant_id = ? and type = ? "
                        + "order by id desc limit 1",
                String.class, tenantId, type);
        return mapper.readTree(payloads.get(0));
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