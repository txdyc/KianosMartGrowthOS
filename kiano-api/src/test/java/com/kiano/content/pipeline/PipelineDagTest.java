package com.kiano.content.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.workerprotocol.ExecutorType;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * Full DAG: lease/complete jobs through the store while a fake worker
 * uploads the output objects to MinIO, then assert on run status and the
 * assets created by the completion handler.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PipelineDagTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private GenerationRunStore runs;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private PipelineService pipeline;

    @Autowired
    private PipelineCompletionHandler handler;

    private long tenantId;
    private long storeId;
    private CurrentUser user;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from comfy_workflow");
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from store_policy");
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        long userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'dag-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        user = new CurrentUser(userId, tenantId, Role.OPERATOR, "dag-op@example.test");
        for (String code : List.of("CUTOUT", "SCENE")) {
            jdbcTemplate.update(
                    "insert into comfy_workflow (tenant_id, code, version, workflow_json, manifest_json, "
                            + "model_refs, status) values (?, ?, 1, '{}', ?, '[]', 'APPROVED')",
                    tenantId, code, "{\"code\":\"" + code + "\"}");
        }
    }

    @Test
    void hero_fullDag_producesTenAssetsInReview() {
        long pid = product("MG-BL200", "Kettle 1.7L", true);
        for (String code : List.of("P1", "P2", "P3", "P4", "P6", "P8")) {
            media(pid, code, "ACCEPTED");
        }
        long runId = pipeline.start(user, pid);

        drainJobs();

        assertThat(runs.latest(tenantId, pid).orElseThrow().status()).isEqualTo("DONE");
        assertThat(assetCount(pid, null)).isEqualTo(10);
        assertThat(assetCount(pid, "PAGE_MAIN")).isEqualTo(1);
        assertThat(assetCount(pid, "PAGE_ANGLE")).isEqualTo(4);
        assertThat(assetCount(pid, "PAGE_SCENE")).isEqualTo(4);
        assertThat(assetCount(pid, "PAGE_INBOX")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from asset where product_id = ? and status = 'IN_REVIEW'",
                Integer.class, pid)).isEqualTo(10);
    }

    @Test
    void standard_twoScenes() {
        long pid = product("MG-FAN16", "Standing Fan 16in", false);
        for (String code : List.of("P1", "P2", "P3", "P4", "P6", "P8")) {
            media(pid, code, "ACCEPTED");
        }
        pipeline.start(user, pid);

        drainJobs();

        // MAIN 1 + ANGLE 4 + SCENE 2 + INBOX 1
        assertThat(assetCount(pid, null)).isEqualTo(8);
        assertThat(assetCount(pid, "PAGE_SCENE")).isEqualTo(2);
    }

    @Test
    void missingP8_noInbox() {
        long pid = product("MG-FAN16", "Standing Fan 16in", false);
        for (String code : List.of("P1", "P2", "P3", "P4", "P6")) {
            media(pid, code, "ACCEPTED");
        }
        pipeline.start(user, pid);

        drainJobs();

        assertThat(assetCount(pid, "PAGE_INBOX")).isZero();
        assertThat(assetCount(pid, "PAGE_MAIN")).isEqualTo(1);
        assertThat(assetCount(pid, "PAGE_SCENE")).isEqualTo(2);
    }

    @Test
    void sceneJob_inputCarriesPromptSeedAndParentOutputs() {
        long pid = product("MG-FAN16", "Standing Fan 16in", false);
        for (String code : List.of("P1", "P2", "P3", "P4", "P6", "P8")) {
            media(pid, code, "ACCEPTED");
        }
        long runId = pipeline.start(user, pid);

        drainJobs();

        GenerationJob sceneJob = jobs.findByRun(tenantId, runId).stream()
                .filter(job -> job.step() == JobStep.SCENE).findFirst().orElseThrow();
        JsonNode input = sceneJob.input();
        assertThat(input.path("workflow").path("code").asString()).isEqualTo("SCENE");
        assertThat(input.path("params").path("positive").asString())
                .endsWith("natural light, high detail");
        assertThat(input.path("params").path("negative").asString()).contains("watermark");
        assertThat(input.path("params").path("seed").asLong()).isNotNegative();

        GenerationJob parent = jobs.findById(sceneJob.parentJobIds().get(0)).orElseThrow();
        assertThat(input.path("inputs").path("image").asString()).isEqualTo(
                parent.output().path("outputs").path("image").path("objectKey").asString());
        assertThat(input.path("inputs").path("mask").asString()).isEqualTo(
                parent.output().path("outputs").path("mask").path("objectKey").asString());
    }

    @Test
    void failedCutout_runPartial_noDownstream() {
        long pid = product("MG-FAN16", "Standing Fan 16in", false);
        for (String code : List.of("P1", "P2", "P3", "P4", "P6", "P8")) {
            media(pid, code, "ACCEPTED");
        }
        long runId = pipeline.start(user, pid);

        // Fail every cutout (non-retryable) instead of completing them.
        GenerationJob job;
        while ((job = jobs.lease("dag-worker",
                Set.of(ExecutorType.COMFYUI, ExecutorType.COMPOSITE)).orElse(null)) != null) {
            jobs.fail(job.id(), "dag-worker", "CUTOUT_EMPTY: nothing to compose", false, false);
        }
        runs.refreshStatus(runId);

        assertThat(runs.latest(tenantId, pid).orElseThrow().status()).isEqualTo("PARTIAL");
        List<GenerationJob> all = jobs.findByRun(tenantId, runId);
        assertThat(all).hasSize(6); // the six cutouts, nothing downstream
        assertThat(all).allMatch(j -> j.step() == JobStep.CUTOUT);
        assertThat(assetCount(pid, null)).isZero();
    }

    @Test
    void rerunAfterReshoot_v2_archivesInReviewV1() {
        long pid = product("MG-BL200", "Kettle 1.7L", false);
        for (String code : List.of("P1", "P2", "P3", "P4", "P6", "P8")) {
            media(pid, code, "ACCEPTED");
        }
        pipeline.start(user, pid);
        drainJobs();
        assertThat(assetCount(pid, "PAGE_MAIN")).isEqualTo(1);

        pipeline.start(user, pid);
        drainJobs();

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from asset where product_id = ? and spec_code = 'PAGE_MAIN' "
                        + "and status = 'IN_REVIEW'", Integer.class, pid)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from asset where product_id = ? and spec_code = 'PAGE_MAIN' "
                        + "and status = 'ARCHIVED'", Integer.class, pid)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select max(version) from asset where product_id = ? and spec_code = 'PAGE_MAIN'",
                Integer.class, pid)).isEqualTo(2);
    }

    /** Leases, uploads fake outputs, completes and fans out until the queue is empty. */
    private void drainJobs() {
        GenerationJob job;
        while ((job = jobs.lease("dag-worker",
                Set.of(ExecutorType.COMFYUI, ExecutorType.COMPOSITE)).orElse(null)) != null) {
            completeJob(job);
        }
    }

    private void completeJob(GenerationJob job) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : job.input().path("outputs").properties()) {
            String objectKey = entry.getValue().asString();
            byte[] bytes = ImageCodec.png("mask".equals(entry.getKey())
                    ? productMask() : fakeImageFor(job));
            storage.put(objectKey, bytes, "image/png");
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("objectKey", objectKey);
            file.put("width", 1600);
            file.put("height", 1600);
            file.put("durationS", null);
            file.put("sha256", "0".repeat(64));
            outputs.put(entry.getKey(), file);
        }
        GenerationJob completed = jobs.complete(job.id(), "dag-worker",
                Map.of("outputs", outputs), 0.0).orElseThrow();
        handler.onSucceeded(completed);
        runs.refreshStatus(completed.runId());
    }

    private static BufferedImage fakeImageFor(GenerationJob job) {
        return switch (job.step()) {
            case SCENE_INPUT, SCENE -> sceneImage(); // same product pixels -> SSIM 1.0
            default -> whitePage(); // cutout (unused) and white pages
        };
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

    /** Grey scene with a red 400x400 product at (200,200). */
    private static BufferedImage sceneImage() {
        BufferedImage img = new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(128, 128, 128));
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

    private int assetCount(long productId, String specCode) {
        if (specCode == null) {
            return jdbcTemplate.queryForObject(
                    "select count(*) from asset where product_id = ?", Integer.class, productId);
        }
        return jdbcTemplate.queryForObject(
                "select count(*) from asset where product_id = ? and spec_code = ?",
                Integer.class, productId, specCode);
    }

    private long product(String sku, String name, boolean hero) {
        long pid = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, ?, 'simple', ?, ?, 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), sku, name);
        if (hero) {
            jdbcTemplate.update(
                    "insert into product_profile (product_id, tenant_id, role_source, content_tier) "
                            + "values (?, ?, 'MANUAL', 'HERO')", pid, tenantId);
        }
        return pid;
    }

    private void media(long productId, String shotCode, String status) {
        jdbcTemplate.update(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, thumb_object_key, content_type, size_bytes, sha256, qc_json, status) "
                        + "values (?, ?, ?, 'PHOTO', ?, ?, null, 'image/jpeg', 1024, ?, '{}', ?)",
                tenantId, productId, shotCode, "MG-X_" + shotCode + ".jpg",
                "t" + tenantId + "/source-media/" + productId + "/" + shotCode + ".png",
                shotCode.hashCode() + "0".repeat(58), status);
    }

    @Autowired
    private PasswordEncoder passwordEncoder;
}
