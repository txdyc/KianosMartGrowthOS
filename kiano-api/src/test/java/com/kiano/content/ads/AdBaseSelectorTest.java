package com.kiano.content.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.ads.AdBaseSelector.BaseImage;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Base image selection for ad statics: pricehook from the approved PAGE_MAIN,
 * problem/trust from the two newest approved PAGE_SCENE shots (shared when only
 * one exists), demo from a V1 frame via {@link FrameExtractor}; a missing base
 * is 409 AD_BASE_MISSING.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdBaseSelectorTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private AdBaseSelector selector;

    @MockitoBean
    private FrameExtractor frameExtractor;

    private long tenantId;
    private long storeId;
    private long productId;
    private byte[] jpeg;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from source_media");
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
                        + "values (?, ?, -6001, 'simple', 'MG-KTL17', 'Morgan 1.7L Kettle', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
        jpeg = ImageCodec.jpeg(square(), 0.9f);
    }

    @Test
    void pricehook_usesLatestApprovedPageMain_real() {
        long mediaId = media(productId, "P1", "PHOTO", "ACCEPTED", null);
        asset(productId, "PAGE_MAIN", "main", 1, "APPROVED", "{\"sourceMediaIds\":[1]}");
        long main = asset(productId, "PAGE_MAIN", "main", 2, "APPROVED",
                "{\"sourceMediaIds\":[" + mediaId + "]}");

        BaseImage base = selector.select(tenantId, productId, AdHook.PRICEHOOK, 0);

        assertThat(base.sourceType()).isEqualTo("real");
        assertThat(base.assetId()).isEqualTo(main);
        assertThat(base.sourceMediaId()).isEqualTo(mediaId);
        assertThat(base.frameTime()).isNull();
        assertThat(base.jpeg()).isEqualTo(jpeg);
    }

    @Test
    void problemAndTrust_useNewestTwoScenes_mixed() {
        long scene1 = asset(productId, "PAGE_SCENE", "scene1", 1, "APPROVED",
                "{\"sourceMediaIds\":[10]}");
        long scene2 = asset(productId, "PAGE_SCENE", "scene2", 2, "APPROVED",
                "{\"sourceMediaIds\":[11]}");

        BaseImage problem = selector.select(tenantId, productId, AdHook.PROBLEM, 0);
        BaseImage trust = selector.select(tenantId, productId, AdHook.TRUST, 0);

        assertThat(problem.assetId()).isEqualTo(scene2);
        assertThat(trust.assetId()).isEqualTo(scene1);
        assertThat(problem.sourceType()).isEqualTo("mixed");
        assertThat(trust.sourceType()).isEqualTo("mixed");
    }

    @Test
    void onlyOneScene_sharedByProblemAndTrust() {
        long scene = asset(productId, "PAGE_SCENE", "scene1", 1, "APPROVED", "{}");

        BaseImage problem = selector.select(tenantId, productId, AdHook.PROBLEM, 0);
        BaseImage trust = selector.select(tenantId, productId, AdHook.TRUST, 0);

        assertThat(problem.assetId()).isEqualTo(scene);
        assertThat(trust.assetId()).isEqualTo(scene);
    }

    @Test
    void publishedMainAndScenes_areEligibleBases() {
        // C3 production publishing flips approved page images to PUBLISHED.
        long main = asset(productId, "PAGE_MAIN", "main", 1, "PUBLISHED", "{}");
        long scene1 = asset(productId, "PAGE_SCENE", "scene1", 1, "PUBLISHED", "{}");
        long scene2 = asset(productId, "PAGE_SCENE", "scene2", 2, "APPROVED", "{}");

        assertThat(selector.select(tenantId, productId, AdHook.PRICEHOOK, 0).assetId())
                .isEqualTo(main);
        assertThat(selector.select(tenantId, productId, AdHook.PROBLEM, 0).assetId())
                .isEqualTo(scene2);
        assertThat(selector.select(tenantId, productId, AdHook.TRUST, 0).assetId())
                .isEqualTo(scene1);
    }

    @Test
    void demo_usesV1FrameCandidate_real() {
        long video = media(productId, "V1", "VIDEO", "ACCEPTED", 12.0);
        List<FrameExtractor.Frame> frames = List.of(
                new FrameExtractor.Frame(0, 1.5, 15.0, jpeg),
                new FrameExtractor.Frame(1, 3.0, 9.0, jpeg),
                new FrameExtractor.Frame(2, 4.5, 6.0, jpeg));
        when(frameExtractor.candidates(any(Path.class), anyDouble())).thenReturn(frames);

        BaseImage best = selector.select(tenantId, productId, AdHook.DEMO, 0);
        BaseImage second = selector.select(tenantId, productId, AdHook.DEMO, 1);
        BaseImage wrapped = selector.select(tenantId, productId, AdHook.DEMO, 4);

        assertThat(best.sourceType()).isEqualTo("real");
        assertThat(best.sourceMediaId()).isEqualTo(video);
        assertThat(best.frameTime()).isEqualTo(1.5);
        assertThat(best.assetId()).isNull();
        assertThat(second.frameTime()).isEqualTo(3.0);
        // candidate index wraps around the sorted candidate list
        assertThat(wrapped.frameTime()).isEqualTo(3.0);
    }

    @Test
    void missingMain_409() {
        assertThatThrownBy(() -> selector.select(tenantId, productId, AdHook.PRICEHOOK, 0))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.getCode()).isEqualTo("AD_BASE_MISSING");
                    assertThat(ex.getDetails()).containsEntry("missing", "PAGE_MAIN");
                });
    }

    @Test
    void missingScene_409() {
        assertThatThrownBy(() -> selector.select(tenantId, productId, AdHook.PROBLEM, 0))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getDetails()).containsEntry("missing", "PAGE_SCENE"));
    }

    @Test
    void missingV1_409() {
        assertThatThrownBy(() -> selector.select(tenantId, productId, AdHook.DEMO, 0))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getDetails()).containsEntry("missing", "V1"));
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
