package com.kiano.content.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.TestcontainersConfiguration;
import com.kiano.content.ads.AdRenderService.RenderOutcome;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactsJson;
import com.kiano.content.policy.PolicySection;
import com.kiano.content.policy.PolicyService;
import com.kiano.content.policy.PolicyService.SectionText;
import com.kiano.content.template.TemplateBootstrap;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
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
 * Ad static rendering: all twelve AD_STATIC assets from approved AD_COPY and
 * bases, the per-hook file names / depends_on_price / price snapshot and the
 * provenance, the 409 AD_PRECONDITIONS (tier, facts, copy, bases, badges), the
 * priceOnly skip mode and the 9:16 TEXT_OUTSIDE_SAFE_AREA flag. Renders with
 * the real Playwright TemplateRenderer; only ffmpeg is mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdRenderServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdRenderService service;

    @Autowired
    private FactSheetService factSheetService;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private TemplateBootstrap bootstrap;

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
    private long videoId;
    private int factVersion;
    private byte[] jpeg;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from llm_call");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store_policy");
        jdbcTemplate.update("delete from template");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        bootstrap.bootstrapAll();
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update("delete from audit_log where tenant_id = ?", tenantId);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, "
                        + "regular_price, sale_price, price, sale_from_at, sale_to_at, status, synced_at) "
                        + "values (?, ?, -6001, 'simple', 'MG-KTL17', 'Morgan 1.7L Kettle', 299, 249, 249, "
                        + "now(), now() + interval '3 days', 'publish', now()) returning id",
                Long.class, tenantId, storeId);
        jdbcTemplate.update("insert into product_profile (product_id, tenant_id, content_tier) "
                + "values (?, ?, 'HERO')", productId, tenantId);
        jpeg = ImageCodec.jpeg(square(), 0.9f);
        long userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'adrender-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        CurrentUser user = new CurrentUser(userId, tenantId, Role.OPERATOR,
                "adrender-op@example.test");
        FactsJson facts = new FactsJson("MG-KTL17", "Electric Kettles", "1.7 L", 350,
                "220-240V", "Stainless steel", "Silver", "1 year", List.of("Kettle", "Base"),
                List.of("Auto shut-off"), List.of(), List.of());
        factSheetService.saveDraft(user, productId, facts,
                Map.of("model", com.kiano.content.facts.FieldSource.P5));
        factVersion = factSheetService.lock(user, productId, 1,
                Set.of("model", "capacity", "powerW", "voltage", "warranty", "inBox")).version();
        policyService.save(user, sectionsWithBadges());
        for (AdHook hook : AdHook.values()) {
            adCopy(hook, overlay(hook), headline(hook), "A 1.7 L kettle with auto shut-off");
        }
        long mainMedia = media(productId, "P1", "PHOTO", "ACCEPTED", null);
        asset(productId, "PAGE_MAIN", "main", 1, "APPROVED",
                "{\"sourceMediaIds\":[" + mainMedia + "]}");
        long sceneMedia = media(productId, "P5", "PHOTO", "ACCEPTED", null);
        asset(productId, "PAGE_SCENE", "scene1", 1, "APPROVED",
                "{\"sourceMediaIds\":[" + sceneMedia + "]}");
        videoId = media(productId, "V1", "VIDEO", "ACCEPTED", 12.0);
        when(frameExtractor.candidates(any(Path.class), anyDouble())).thenReturn(List.of(
                new FrameExtractor.Frame(0, 1.5, 15.0, jpeg),
                new FrameExtractor.Frame(1, 3.0, 9.0, jpeg),
                new FrameExtractor.Frame(2, 4.5, 6.0, jpeg)));
    }

    @Test
    void renderAll_createsTwelveAssetsWithPriceAndProvenance() throws Exception {
        RenderOutcome outcome = service.renderAll(tenantId, productId, null, 0, false);

        List<String> expected = new ArrayList<>();
        for (AdHook hook : AdHook.values()) {
            for (AdSize size : AdSize.values()) {
                expected.add(hook.wire() + "-" + size.label());
            }
        }
        assertThat(outcome.rendered()).isEqualTo(expected);
        assertThat(outcome.skipped()).isEmpty();

        List<AssetEntity> assets = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getSpecCode, "AD_STATIC"));
        assertThat(assets).hasSize(12);
        for (AdHook hook : AdHook.values()) {
            for (AdSize size : AdSize.values()) {
                String variant = hook.wire() + "-" + size.label();
                AssetEntity asset = assets.stream()
                        .filter(a -> variant.equals(a.getVariant())).findFirst().orElseThrow();
                assertThat(asset.getStatus()).isEqualTo("IN_REVIEW");
                assertThat(asset.getFactVersion()).isEqualTo(factVersion);
                String type = hook == AdHook.PROBLEM || hook == AdHook.TRUST
                        ? "mixed" : "real";
                assertThat(asset.getFileName()).isEqualTo("MG-KTL17_" + hook.wire() + "_" + type
                        + "_" + size.label() + "_v1.jpg");
                if (hook == AdHook.PRICEHOOK) {
                    assertThat(asset.getDependsOnPrice()).isTrue();
                    assertThat(asset.getPriceSnapshot()).isEqualByComparingTo("249");
                } else {
                    assertThat(asset.getDependsOnPrice()).isFalse();
                    assertThat(asset.getPriceSnapshot()).isNull();
                }
                JsonNode provenance = mapper.readTree(asset.getProvenanceJson());
                assertThat(provenance.path("template").path("code").asText())
                        .isEqualTo("AD_" + hook.name());
                assertThat(provenance.path("template").path("version").asInt()).isEqualTo(1);
                assertThat(provenance.path("adCopyAssetId").isNumber()).isTrue();
                if (hook == AdHook.DEMO) {
                    assertThat(provenance.path("baseAssetId").isNull()).isTrue();
                } else {
                    assertThat(provenance.path("baseAssetId").isNumber()).isTrue();
                }
                assertThat(provenance.path("factVersion").asInt()).isEqualTo(factVersion);
                assertThat(provenance.path("price").path("current").asText())
                        .isEqualTo("GH₵ 249");
                if (hook == AdHook.PRICEHOOK) {
                    assertThat(provenance.path("price").path("strike").asText())
                            .isEqualTo("GH₵ 299");
                    assertThat(provenance.path("price").path("snapshot").decimalValue())
                            .isEqualByComparingTo(java.math.BigDecimal.valueOf(249));
                }
                if (hook == AdHook.DEMO) {
                    assertThat(provenance.path("frameTime").isNumber()).isTrue();
                    assertThat(provenance.path("sourceMediaId").asLong()).isEqualTo(videoId);
                } else {
                    assertThat(provenance.path("frameTime").isNull()).isTrue();
                }
                JsonNode precheck = mapper.readTree(asset.getPrecheckJson());
                assertThat(precheck.path("flags").toString()).isEqualTo("[]");
            }
        }
    }

    @Test
    void nonHero_409() {
        jdbcTemplate.update("delete from product_profile where product_id = ?", productId);

        assertThatThrownBy(() -> service.renderAll(tenantId, productId, null, 0, false))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertMissing(ex, "NOT_HERO"));
    }

    @Test
    void missingAdCopy_409() {
        jdbcTemplate.update("delete from asset where spec_code = 'AD_COPY' and variant = 'demo'");

        assertThatThrownBy(() -> service.renderAll(tenantId, productId, null, 0, false))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertMissing(ex, "AD_COPY:demo"));
    }

    @Test
    void missingBase_409() {
        jdbcTemplate.update("delete from asset where spec_code = 'PAGE_MAIN'");
        assertThatThrownBy(() -> service.renderAll(tenantId, productId, null, 0, false))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertMissing(ex, "PAGE_MAIN"));

        jdbcTemplate.update("delete from asset where spec_code = 'PAGE_SCENE'");
        assertThatThrownBy(() -> service.renderAll(tenantId, productId, null, 0, false))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertMissing(ex, "PAGE_SCENE"));

        jdbcTemplate.update("delete from source_media where product_id = ?", productId);
        assertThatThrownBy(() -> service.renderAll(tenantId, productId, null, 0, false))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertMissing(ex, "V1"));
    }

    @Test
    void missingBadges_409() {
        jdbcTemplate.update("update store_policy set sections_json = ?::jsonb where tenant_id = ?",
                badgesOnlySections(), tenantId);

        assertThatThrownBy(() -> service.renderAll(tenantId, productId, null, 0, false))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertMissing(ex, "POLICY_BADGES_MISSING"));
    }

    @Test
    void priceOnly_skipsPreconditions_andSkipsVariantsWithoutCopy() {
        jdbcTemplate.update("delete from asset where spec_code = 'AD_COPY' and variant = 'demo'");

        RenderOutcome outcome = service.renderAll(tenantId, productId, null, 0, true);

        assertThat(outcome.skipped()).containsKeys("demo-1080x1080", "demo-1080x1350",
                "demo-1080x1920");
        assertThat(outcome.skipped().values()).containsOnly("AD_COPY_MISSING");
        assertThat(outcome.rendered()).hasSize(9);
    }

    @Test
    void nineBySixteen_outsideSafeArea_flagged_otherSizesNot() throws Exception {
        jdbcTemplate.update("update asset set text_body = ? where spec_code = 'AD_COPY' "
                        + "and variant = 'pricehook'",
                new AdCopyText("Amazing product feature ".repeat(500),
                        headline(AdHook.PRICEHOOK),
                        "A 1.7 L kettle with auto shut-off").toJson());

        service.renderAll(tenantId, productId,
                List.of("pricehook-1080x1920", "pricehook-1080x1080"), 0, false);

        AssetEntity vertical = latestAdStatic("pricehook-1080x1920");
        JsonNode verticalPrecheck = mapper.readTree(vertical.getPrecheckJson());
        assertThat(verticalPrecheck.path("flags").toString()).contains("TEXT_OUTSIDE_SAFE_AREA");
        AssetEntity square = latestAdStatic("pricehook-1080x1080");
        assertThat(mapper.readTree(square.getPrecheckJson()).path("flags").toString())
                .isEqualTo("[]");
    }

    @Test
    void priceChange_onlyPriceDiffers_autoApproved() {
        long mainId = jdbcTemplate.queryForObject(
                "select id from asset where spec_code = 'PAGE_MAIN'", Long.class);
        long copyId = jdbcTemplate.queryForObject(
                "select id from asset where spec_code = 'AD_COPY' and variant = 'pricehook'",
                Long.class);
        // previous APPROVED render with only the price different from what we render now
        long oldId = adStaticSeeded("pricehook-1080x1080", 1, "APPROVED",
                "{\"template\":{\"code\":\"AD_PRICEHOOK\",\"version\":1},"
                        + "\"adCopyAssetId\":" + copyId + ",\"baseAssetId\":" + mainId + ","
                        + "\"sourceMediaId\":null,\"frameTime\":null,"
                        + "\"price\":{\"snapshot\":199}}");

        service.renderAll(tenantId, productId, List.of("pricehook-1080x1080"), 0, true,
                Map.of("pricehook-1080x1080", oldId));

        String status = jdbcTemplate.queryForObject(
                "select status from asset where spec_code = 'AD_STATIC' "
                        + "and variant = 'pricehook-1080x1080' and version = 2",
                String.class);
        assertThat(status).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'AD_AUTO_APPROVED_PRICE_CHANGE' "
                        + "and tenant_id = ?", Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    void priceChange_copyChanged_notAutoApproved() {
        long mainId = jdbcTemplate.queryForObject(
                "select id from asset where spec_code = 'PAGE_MAIN'", Long.class);
        long oldCopyId = jdbcTemplate.queryForObject(
                "select id from asset where spec_code = 'AD_COPY' and variant = 'pricehook'",
                Long.class);
        long oldId = adStaticSeeded("pricehook-1080x1080", 1, "APPROVED",
                "{\"template\":{\"code\":\"AD_PRICEHOOK\",\"version\":1},"
                        + "\"adCopyAssetId\":" + oldCopyId + ",\"baseAssetId\":" + mainId + "}");
        // a new approved AD_COPY supersedes the one the old render used
        AdCopyText newCopy = new AdCopyText("New value angle", headline(AdHook.PRICEHOOK),
                "A 1.7 L kettle with auto shut-off");
        jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "text_body, content_json, status, precheck_json, provenance_json, fact_version) "
                        + "values (?, ?, 'AD_COPY', 'pricehook', 2, 'TEXT', ?, ?::jsonb, 'APPROVED', "
                        + "'{}'::jsonb, ?::jsonb, ?) returning id",
                Long.class, tenantId, productId, newCopy.toJson(),
                "{\"hook\":\"pricehook\"}", "{}", factVersion);

        service.renderAll(tenantId, productId, List.of("pricehook-1080x1080"), 0, true,
                Map.of("pricehook-1080x1080", oldId));

        assertThat(jdbcTemplate.queryForObject(
                "select status from asset where id in (select id from asset where spec_code = 'AD_STATIC' "
                        + "and variant = 'pricehook-1080x1080' order by version desc limit 1)",
                String.class)).isEqualTo("IN_REVIEW");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'AD_AUTO_APPROVED_PRICE_CHANGE' "
                        + "and tenant_id = ?", Integer.class, tenantId)).isZero();
    }

    @Test
    void publishedPageImages_satisfyPreconditions_andRenderAllTwelve() {
        // C3 production publishing flips the approved page images to PUBLISHED
        jdbcTemplate.update("update asset set status = 'PUBLISHED' "
                + "where spec_code in ('PAGE_MAIN', 'PAGE_SCENE')");

        RenderOutcome outcome = service.renderAll(tenantId, productId, null, 0, false);

        assertThat(outcome.rendered()).hasSize(12);
        assertThat(outcome.skipped()).isEmpty();
    }

    @Test
    void fullRender_extractsDemoFramesOnce_forAllThreeSizes() {
        service.renderAll(tenantId, productId, null, 0, false);

        verify(frameExtractor, times(1)).candidates(any(Path.class), anyDouble());
    }

    @Test
    void demoProvenance_recordsFrameCandidate() throws Exception {
        service.renderAll(tenantId, productId, List.of("demo-1080x1080"), 1, false);

        JsonNode provenance = mapper.readTree(latestAdStatic("demo-1080x1080").getProvenanceJson());
        assertThat(provenance.path("frameCandidate").asInt()).isEqualTo(1);
        assertThat(provenance.path("frameTime").asDouble()).isEqualTo(3.0);
    }

    @Test
    void priceOnly_followUp_restalesApprovedOldPrice_andAutoApprovesFromIt() {
        // the first re-render already auto-approved v2 at an intermediate price (279)
        long v2 = adStaticPriced("pricehook-1080x1080", 2, "APPROVED", 279,
                samePricehookProvenance());

        service.renderAll(tenantId, productId, List.of("pricehook-1080x1080"), 0, true, null);

        assertThat(jdbcTemplate.queryForObject("select status from asset where id = ?",
                String.class, v2)).isEqualTo("STALE");
        AssetEntity latest = latestAdStatic("pricehook-1080x1080");
        assertThat(latest.getVersion()).isEqualTo(3);
        assertThat(latest.getStatus()).isEqualTo("APPROVED");
        assertThat(latest.getPriceSnapshot()).isEqualByComparingTo("249");
    }

    @Test
    void priceOnly_variantAlreadyAtCurrentPrice_isNotRerendered() {
        adStaticPriced("pricehook-1080x1080", 1, "APPROVED", 249, samePricehookProvenance());

        RenderOutcome outcome = service.renderAll(tenantId, productId,
                List.of("pricehook-1080x1080"), 0, true, null);

        assertThat(outcome.rendered()).isEmpty();
        assertThat(outcome.skipped()).containsEntry("pricehook-1080x1080", "UP_TO_DATE");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from asset where spec_code = 'AD_STATIC'", Integer.class))
                .isEqualTo(1);
    }

    // ---- helpers ----

    /** Provenance matching what a pricehook render produces now, apart from the price. */
    private String samePricehookProvenance() {
        long mainId = jdbcTemplate.queryForObject(
                "select id from asset where spec_code = 'PAGE_MAIN'", Long.class);
        long copyId = jdbcTemplate.queryForObject(
                "select id from asset where spec_code = 'AD_COPY' and variant = 'pricehook'",
                Long.class);
        return "{\"template\":{\"code\":\"AD_PRICEHOOK\",\"version\":1},"
                + "\"adCopyAssetId\":" + copyId + ",\"baseAssetId\":" + mainId + ","
                + "\"sourceMediaId\":null,\"frameTime\":null}";
    }

    /** A price-dependent AD_STATIC row with a price snapshot. */
    private long adStaticPriced(String variant, int version, String status, int snapshot,
            String provenanceJson) {
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json, depends_on_price, price_snapshot) "
                        + "values (?, ?, 'AD_STATIC', ?, ?, 'IMAGE', ?, '{}'::jsonb, ?::jsonb, true, ?) "
                        + "returning id",
                Long.class, tenantId, productId, variant, version, status, provenanceJson,
                java.math.BigDecimal.valueOf(snapshot));
    }

    @SuppressWarnings("unchecked")
    private static void assertMissing(ApiException ex, String... expected) {
        assertThat(ex.getCode()).isEqualTo("AD_PRECONDITIONS");
        List<String> missing = (List<String>) ex.getDetails().get("missing");
        assertThat(missing).contains(expected);
    }

    private AssetEntity latestAdStatic(String variant) {
        List<AssetEntity> rows = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getSpecCode, "AD_STATIC")
                .eq(AssetEntity::getVariant, variant)
                .orderByDesc(AssetEntity::getVersion)
                .last("limit 1"));
        return rows.get(0);
    }

    private static String overlay(AdHook hook) {
        return switch (hook) {
            case PRICEHOOK -> "Everyday value for your home";
            case PROBLEM -> "Tired of slow ironing?";
            case DEMO -> "See it steam in seconds";
            default -> "Why buy with confidence";
        };
    }

    private static String headline(AdHook hook) {
        return switch (hook) {
            case PRICEHOOK -> "Great kettle, fair price";
            case PROBLEM -> "Boils in minutes";
            case DEMO -> "Watch it in action";
            default -> "Safe & reliable";
        };
    }

    private Map<PolicySection, SectionText> sectionsWithBadges() {
        Map<PolicySection, SectionText> sections = new EnumMap<>(PolicySection.class);
        sections.put(PolicySection.DELIVERY, new SectionText("Delivery", "1-3 days", "Fast delivery"));
        sections.put(PolicySection.COD, new SectionText("COD", "Pay on receipt", "COD"));
        sections.put(PolicySection.MOMO, new SectionText("MoMo", "MTN MoMo", "MTN MoMo"));
        sections.put(PolicySection.WARRANTY, new SectionText("Warranty", "1 year", null));
        sections.put(PolicySection.RETURNS, new SectionText("Returns", "7 days", null));
        return sections;
    }

    /** Latest policy without badges (only title/body) - a full JSON map. */
    private String badgesOnlySections() {
        try {
            Map<String, Object> sections = new java.util.LinkedHashMap<>();
            for (PolicySection section : PolicySection.values()) {
                sections.put(section.name(), Map.of("title", "Title " + section,
                        "body", "Body " + section));
            }
            return mapper.writeValueAsString(sections);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long adCopy(AdHook hook, String overlay, String headline, String primary) {
        AdCopyText text = new AdCopyText(overlay, headline, primary);
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "text_body, content_json, status, precheck_json, provenance_json, fact_version) "
                        + "values (?, ?, 'AD_COPY', ?, 1, 'TEXT', ?, ?::jsonb, 'APPROVED', "
                        + "'{}'::jsonb, ?::jsonb, ?) returning id",
                Long.class, tenantId, productId, hook.wire(), text.toJson(),
                "{\"hook\":\"" + hook.wire() + "\"}", "{}", factVersion);
    }

    /** A pre-rendered AD_STATIC row (no storage object) for auto-approval seeding. */
    private long adStaticSeeded(String variant, int version, String status, String provenanceJson) {
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json) "
                        + "values (?, ?, 'AD_STATIC', ?, ?, 'IMAGE', ?, '{}'::jsonb, ?::jsonb) "
                        + "returning id",
                Long.class, tenantId, productId, variant, version, status, provenanceJson);
    }

    private long media(long productId, String shotCode, String kind, String status,
            Double durationS) {
        String key = "t" + tenantId + "/source-media/" + productId + "/" + shotCode + ".bin";
        storage.put(key, jpeg, "application/octet-stream");
        return jdbcTemplate.queryForObject(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, thumb_object_key, content_type, size_bytes, sha256, qc_json, status, duration_s) "
                        + "values (?, ?, ?, ?, ?, ?, null, ?, 1024, ?, '{}', ?, ?) returning id",
                Long.class, tenantId, productId, shotCode, kind,
                shotCode + ".mp4", key, "video/mp4", shotCode.hashCode() + "0".repeat(58),
                status, durationS);
    }

    private long asset(long productId, String specCode, String variant, int version, String status,
            String provenanceJson) {
        String prefix = "t" + tenantId + "/assets/" + productId + "/" + specCode + "/" + variant
                + "/v" + version;
        storage.put(prefix + ".jpg", jpeg, "image/jpeg");
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "object_key, thumb_object_key, width, height, status, precheck_json, provenance_json) "
                        + "values (?, ?, ?, ?, ?, 'IMAGE', ?, ?, 1080, 1080, ?, '{}'::jsonb, ?::jsonb) "
                        + "returning id",
                Long.class, tenantId, productId, specCode, variant, version,
                prefix + ".jpg", prefix + "_thumb.jpg", status, provenanceJson);
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
