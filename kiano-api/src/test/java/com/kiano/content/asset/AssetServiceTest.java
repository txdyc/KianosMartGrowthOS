package com.kiano.content.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.asset.AssetService.AssetView;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.workerprotocol.JobStep;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AssetService against Testcontainers PG + MinIO: SUCCEEDED jobs are turned
 * into versioned IN_REVIEW assets with precheck metrics, thumbnails, export
 * file names and audit rows.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AssetServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private GenerationRunStore runs;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private AssetService assets;

    private long tenantId;
    private long productId;
    private long runId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'", Long.class);
        Long storeId = jdbcTemplate.query(
                "select id from store where tenant_id = ? order by id limit 1",
                (rs, rowNum) -> rs.getLong("id"), tenantId).stream().findFirst().orElseGet(
                        () -> jdbcTemplate.queryForObject(
                                "insert into store (tenant_id, platform, base_url) "
                                        + "values (?, 'woocommerce', 'http://woo.test') returning id",
                                Long.class, tenantId));
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, ?, 'simple', 'MG-BL200', 'Asset Test Product', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000));
        runId = runs.create(tenantId, productId, null);
    }

    @Test
    void createFromJob_storesJpegThumbVersion1_inReview() {
        String key = put(whitePage());
        GenerationJob job = whiteMainJob(key);

        AssetView view = assets.createFromJob(job, "MG-BL200",
                Map.of("sourceMediaIds", List.of(42L), "workflow", "WHITE_MAIN"));

        assertThat(view.version()).isEqualTo(1);
        assertThat(view.status()).isEqualTo("IN_REVIEW");
        assertThat(view.specCode()).isEqualTo("PAGE_MAIN");
        assertThat(view.variant()).isEqualTo("A");
        assertThat(view.flags()).isEmpty();
        assertThat((double) view.metrics().get("occupancy")).isEqualTo(0.825);
        assertThat(view.fileName()).isEqualTo("MG-BL200_page-main_real_1600x1600_v1.jpg");
        assertThat(view.sourceMediaId()).isEqualTo(42L);
        assertThat(view.createdAt()).isNotNull();

        String fullKey = "t" + tenantId + "/assets/" + productId + "/PAGE_MAIN/A/v1.jpg";
        String thumbKey = "t" + tenantId + "/assets/" + productId + "/PAGE_MAIN/A/v1_thumb.jpg";
        assertThat(storage.exists(fullKey)).isTrue();
        assertThat(storage.exists(thumbKey)).isTrue();
        byte[] jpeg = storage.download(fullKey);
        assertThat(jpeg[0] & 0xFF).isEqualTo(0xFF);
        assertThat(jpeg[1] & 0xFF).isEqualTo(0xD8);
        assertThat(ImageCodec.read(storage.download(thumbKey)).getWidth()).isEqualTo(400);

        Integer audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'ASSET_CREATED' and target_id = ?",
                Integer.class, String.valueOf(view.id()));
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void secondVersion_archivesPreviousInReview_keepsApproved() {
        AssetView v1 = assets.createFromJob(whiteMainJob(put(whitePage())), "MG-BL200", Map.of());
        jdbcTemplate.update("update asset set status = 'APPROVED' where id = ?", v1.id());
        AssetView v2 = assets.createFromJob(whiteMainJob(put(whitePage())), "MG-BL200", Map.of());
        AssetView v3 = assets.createFromJob(whiteMainJob(put(whitePage())), "MG-BL200", Map.of());

        assertThat(status(v1.id())).isEqualTo("APPROVED");
        assertThat(status(v2.id())).isEqualTo("ARCHIVED");
        assertThat(status(v3.id())).isEqualTo("IN_REVIEW");
        assertThat(v3.version()).isEqualTo(3);
    }

    @Test
    void sceneAsset_usesParentSceneInputForSsim() {
        String sceneKey = put(sceneImage(new Color(128, 128, 128)));
        String maskKey = put(productMask());
        Map<String, Object> parentOutputs = new LinkedHashMap<>();
        parentOutputs.put("image", imageOutput(sceneKey));
        parentOutputs.put("mask", imageOutput(maskKey));
        GenerationJob parent = succeededJob(JobStep.SCENE_INPUT, "scene1",
                Map.of("inputs", Map.of(), "outputs", Map.of("image", sceneKey, "mask", maskKey)),
                parentOutputs, List.of());

        // Same red product pasted onto a different background.
        String outputKey = put(sceneImage(new Color(30, 60, 120)));
        GenerationJob job = succeededJob(JobStep.SCENE, "scene1",
                Map.of("inputs", Map.of("image", "scene-in.png", "mask", "mask-in.png"),
                        "outputs", Map.of("image", outputKey)),
                Map.of("image", imageOutput(outputKey)), List.of(parent.id()));

        AssetView view = assets.createFromJob(job, "MG-BL200",
                Map.of("sourceMediaIds", List.of(7L)));

        assertThat(view.specCode()).isEqualTo("PAGE_SCENE");
        assertThat(view.flags()).isEmpty();
        assertThat((double) view.metrics().get("ssim")).isGreaterThan(0.99);
        assertThat(view.metrics().get("ocr")).isEqualTo("SKIPPED");
        assertThat(view.fileName()).isEqualTo("MG-BL200_page-scene1_mixed_1600x1600_v1.jpg");
        assertThat(view.sourceMediaId()).isEqualTo(7L);
        String sceneFullKey = "t" + tenantId + "/assets/" + productId + "/PAGE_SCENE/scene1/v1.jpg";
        assertThat(storage.exists(sceneFullKey)).isTrue();
    }

    private GenerationJob whiteMainJob(String outputKey) {
        return succeededJob(JobStep.WHITE_MAIN, "A",
                Map.of("inputs", Map.of(), "outputs", Map.of("image", outputKey)),
                Map.of("image", imageOutput(outputKey)), List.of());
    }

    /** Creates the job, leases it as a fake worker and completes it with outputs. */
    private GenerationJob succeededJob(JobStep step, String variant, Object input,
            Map<String, Object> outputs, List<Long> parents) {
        long jobId = jobs.create(tenantId, productId, runId, step, variant, input, parents);
        jobs.lease("asset-test-worker", Set.of(step.executor()));
        return jobs.complete(jobId, "asset-test-worker", Map.of("outputs", outputs), 0.0)
                .orElseThrow();
    }

    private Map<String, Object> imageOutput(String objectKey) {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("objectKey", objectKey);
        image.put("width", 1600);
        image.put("height", 1600);
        image.put("durationS", null);
        image.put("sha256", "0".repeat(64));
        return image;
    }

    private String put(BufferedImage img) {
        String key = "asset-test/" + System.nanoTime() + "/out.png";
        storage.put(key, ImageCodec.png(img), "image/png");
        return key;
    }

    private String status(long assetId) {
        return jdbcTemplate.queryForObject("select status from asset where id = ?", String.class, assetId);
    }

    /** 1600x1600 white canvas with a centered red 1320 square (occupancy 0.825). */
    private static BufferedImage whitePage() {
        BufferedImage img = new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1600, 1600);
        g.setColor(Color.RED);
        g.fillRect(140, 140, 1320, 1320);
        g.dispose();
        return img;
    }

    /** 1600x1600 scene with a red 400x400 product at (200,200). */
    private static BufferedImage sceneImage(Color background) {
        BufferedImage img = new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, 1600, 1600);
        g.setColor(Color.RED);
        g.fillRect(200, 200, 400, 400);
        g.dispose();
        return img;
    }

    /** White 400x400 product mask at (200,200) on black. */
    private static BufferedImage productMask() {
        BufferedImage img = new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 1600, 1600);
        g.setColor(Color.WHITE);
        g.fillRect(200, 200, 400, 400);
        g.dispose();
        return img;
    }
}
