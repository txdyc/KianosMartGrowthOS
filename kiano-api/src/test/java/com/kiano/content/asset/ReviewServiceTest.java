package com.kiano.content.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.asset.ReviewService.Decision;
import com.kiano.content.asset.ReviewService.DecisionResult;
import com.kiano.content.asset.ReviewService.ReviewItem;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.content.pipeline.PipelineCompletionHandler;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * ReviewService: listing order (flagged first, then sku/spec/variant), the
 * approve/reject rules, REGENERATE job recreation (scene keeps its scene
 * input, angle re-cuts only that angle) and approve-remaining.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReviewServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReviewService service;

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private GenerationRunStore runs;

    @Autowired
    private PipelineCompletionHandler handler;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;
    private long storeId;
    private long userId;
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
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'review-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        user = new CurrentUser(userId, tenantId, Role.OPERATOR, "review-op@example.test");
        for (String code : List.of("CUTOUT", "SCENE")) {
            jdbcTemplate.update(
                    "insert into comfy_workflow (tenant_id, code, version, workflow_json, manifest_json, "
                            + "model_refs, status) values (?, ?, 1, '{}', ?, '[]', 'APPROVED')",
                    tenantId, code, "{\"code\":\"" + code + "\"}");
        }
    }

    @Test
    void list_flaggedFirst_thenSpecOrder() {
        long productA = product("MG-A", "Kettle A");
        long productB = product("MG-B", "Kettle B");
        long mediaA = media(productA, "P1", "ACCEPTED");
        long flaggedB = asset(productB, "PAGE_ANGLE", "P3", 1, "IN_REVIEW",
                "[\"OCCUPANCY_OUT_OF_RANGE\"]", "{}");
        long aMain = asset(productA, "PAGE_MAIN", "main", 1, "IN_REVIEW", "[]",
                "{\"sourceMediaIds\":[" + mediaA + "]}");
        long aAngle2 = asset(productA, "PAGE_ANGLE", "P2", 1, "IN_REVIEW", "[]", "{}");
        long aAngle6 = asset(productA, "PAGE_ANGLE", "P6", 1, "IN_REVIEW", "[]", "{}");
        long aScene1 = asset(productA, "PAGE_SCENE", "scene1", 1, "IN_REVIEW", "[]", "{}");
        long aScene2 = asset(productA, "PAGE_SCENE", "scene2", 1, "IN_REVIEW", "[]", "{}");
        long aInbox = asset(productA, "PAGE_INBOX", "P8", 1, "IN_REVIEW", "[]", "{}");
        long bMain = asset(productB, "PAGE_MAIN", "main", 1, "IN_REVIEW", "[]", "{}");
        asset(productA, "PAGE_ANGLE", "P4", 1, "REJECTED", "[]", "{}");

        List<ReviewItem> items = service.list(user, null, "IN_REVIEW", null);

        assertThat(items).extracting(ReviewItem::assetId)
                .containsExactly(flaggedB, aMain, aAngle2, aAngle6, aScene1, aScene2, aInbox, bMain);
        ReviewItem main = items.stream()
                .filter(item -> item.assetId() == aMain).findFirst().orElseThrow();
        assertThat(main.sku()).isEqualTo("MG-A");
        assertThat(main.productName()).isEqualTo("Kettle A");
        assertThat(main.specCode()).isEqualTo("PAGE_MAIN");
        assertThat(main.version()).isEqualTo(1);
        assertThat(main.flags()).isEmpty();
        assertThat(main.metrics()).containsEntry("occupancy", 0.82);
        assertThat(main.imageUrl()).contains("/v1.jpg");
        assertThat(main.thumbUrl()).contains("_thumb.jpg");
        assertThat(main.sourceThumbUrl()).contains("source-media");
        assertThat(main.sourceUrl()).contains("source-media");
        assertThat(main.fileName()).isEqualTo("MG-A_page-main_real_1600x1600_v1.jpg");

        // Product filter narrows to one product, unfiltered returns everything.
        assertThat(service.list(user, productB, null, null)).hasSize(2);
        assertThat(service.list(user, null, null, null)).hasSize(9);
    }

    @Test
    void approve_setsApproved_andRecordsReview() {
        long pid = product("MG-A", "Kettle A");
        long aid = asset(pid, "PAGE_MAIN", "main", 1, "IN_REVIEW", "[]", "{}");

        DecisionResult result = service.decide(user, aid, Decision.APPROVE, List.of(), null);

        assertThat(result.item().status()).isEqualTo("APPROVED");
        assertThat(result.runId()).isNull();
        assertThat(assetStatus(aid)).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from asset_review where asset_id = ? and decision = 'APPROVE' "
                        + "and reviewer = ?", Integer.class, aid, userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'ASSET_REVIEWED' and target_id = ?",
                Integer.class, String.valueOf(aid))).isEqualTo(1);
    }

    @Test
    void reject_requiresReason_400() {
        long pid = product("MG-A", "Kettle A");
        long aid = asset(pid, "PAGE_MAIN", "main", 1, "IN_REVIEW", "[]", "{}");

        assertThatThrownBy(() -> service.decide(user, aid, Decision.REJECT, List.of(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getCode()).isEqualTo("VALIDATION_FAILED");
                });
        assertThat(assetStatus(aid)).isEqualTo("IN_REVIEW");
        assertThat(reviewCount(aid)).isZero();
    }

    @Test
    void reject_withReasons_rejected() {
        long pid = product("MG-A", "Kettle A");
        long aid = asset(pid, "PAGE_SCENE", "scene1", 1, "IN_REVIEW",
                "[\"PRODUCT_MISMATCH\"]", "{}");

        DecisionResult result = service.decide(user, aid, Decision.REJECT,
                List.of(RejectReason.AI_ARTIFACT, RejectReason.LOW_QUALITY), "edges look dirty");

        assertThat(result.item().status()).isEqualTo("REJECTED");
        assertThat(assetStatus(aid)).isEqualTo("REJECTED");
        assertThat(jdbcTemplate.queryForObject(
                "select reason_codes from asset_review where asset_id = ?", String.class, aid))
                .contains("AI_ARTIFACT").contains("LOW_QUALITY");
        assertThat(jdbcTemplate.queryForObject(
                "select comment from asset_review where asset_id = ?", String.class, aid))
                .isEqualTo("edges look dirty");
    }

    @Test
    void decision_onNonReview_409() {
        long pid = product("MG-A", "Kettle A");
        long aid = asset(pid, "PAGE_MAIN", "main", 1, "APPROVED", "[]", "{}");

        assertThatThrownBy(() -> service.decide(user, aid, Decision.APPROVE, List.of(), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getCode()).isEqualTo("ASSET_NOT_IN_REVIEW");
                });
    }

    @Test
    void regenerate_scene_newJobNewSeedSameSceneInput() {
        long pid = product("MG-A", "Kettle A");
        long mediaId = media(pid, "P2", "ACCEPTED");
        long runId = runs.create(tenantId, pid, userId);

        // A completed SCENE_INPUT composite with image + mask outputs.
        String sceneImageKey = "test/scene-input/image.png";
        String maskKey = "test/scene-input/mask.png";
        storage.put(sceneImageKey, ImageCodec.png(sceneImage()), "image/png");
        storage.put(maskKey, ImageCodec.png(productMask()), "image/png");
        long sceneInputId = jobs.create(tenantId, pid, runId, JobStep.SCENE_INPUT, "scene1",
                Map.of("sourceMediaId", mediaId, "shotCode", "P2", "inputs",
                        Map.of("cutout", "test/cutout.png"), "outputs", Map.of()),
                List.of());
        GenerationJob leasedInput = jobs.lease("review-worker", Set.of(ExecutorType.COMPOSITE))
                .orElseThrow();
        // Completed directly without the pipeline handler: the scene job under
        // test is created manually below with a fixed seed and provenance.
        jobs.complete(leasedInput.id(), "review-worker",
                Map.of("outputs", Map.of("image", file(sceneImageKey), "mask", file(maskKey))),
                0.0).orElseThrow();
        runs.refreshStatus(runId);

        // A completed SCENE job that produced the asset under review.
        long sceneJobId = jobs.create(tenantId, pid, runId, JobStep.SCENE, "scene1",
                Map.of(
                        "sourceMediaId", mediaId, "shotCode", "P2",
                        "workflows", List.of(),
                        "workflow", Map.of("code", "SCENE", "version", 1, "json", Map.of(),
                                "manifest", Map.of("code", "SCENE")),
                        "models", List.of(),
                        "params", Map.of("positive", "bright kitchen", "negative", "text", "seed", 42),
                        "jobIds", List.of(999L, sceneInputId),
                        "inputs", Map.of("image", sceneImageKey, "mask", maskKey),
                        "outputs", Map.of()),
                List.of(sceneInputId));
        GenerationJob leasedScene = jobs.lease("review-worker", Set.of(ExecutorType.COMFYUI))
                .orElseThrow();
        assertThat(leasedScene.id()).isEqualTo(sceneJobId);
        // Completed without the handler: the asset under review is inserted
        // manually below with the exact provenance under test.
        jobs.complete(leasedScene.id(), "review-worker",
                Map.of("outputs", Map.of("image", file("test/scene/out.png"))), 0.0)
                .orElseThrow();
        runs.refreshStatus(runId);

        String provenance = "{\"sourceMediaIds\":[" + mediaId + "],\"seed\":42,"
                + "\"jobIds\":[999," + sceneInputId + "," + sceneJobId + "]}";
        long aid = asset(pid, "PAGE_SCENE", "scene1", 1, "IN_REVIEW", "[]", provenance);

        DecisionResult result = service.decide(user, aid, Decision.REGENERATE, List.of(), "redo");

        assertThat(result.runId()).isNotNull();
        assertThat(assetStatus(aid)).isEqualTo("ARCHIVED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from asset_review where asset_id = ? and decision = 'REGENERATE'",
                Integer.class, aid)).isEqualTo(1);

        List<GenerationJob> newJobs = jobs.findByRun(tenantId, result.runId());
        assertThat(newJobs).hasSize(1);
        GenerationJob scene = newJobs.get(0);
        assertThat(scene.step()).isEqualTo(JobStep.SCENE);
        assertThat(scene.variant()).isEqualTo("scene1");
        JsonNode params = scene.input().path("params");
        assertThat(params.path("positive").asString()).isEqualTo("bright kitchen");
        assertThat(params.path("negative").asString()).isEqualTo("text");
        assertThat(params.path("seed").asLong()).isNotEqualTo(42).isNotNegative();
        assertThat(scene.input().path("inputs").path("image").asString()).isEqualTo(sceneImageKey);
        assertThat(scene.input().path("inputs").path("mask").asString()).isEqualTo(maskKey);
        assertThat(scene.parentJobIds()).containsExactly(sceneInputId);
    }

    @Test
    void regenerate_angle_reCutsOnlyThatAngle() {
        long pid = product("MG-A", "Kettle A");
        long mediaId = media(pid, "P2", "ACCEPTED");
        long aid = asset(pid, "PAGE_ANGLE", "P2", 1, "IN_REVIEW", "[]",
                "{\"sourceMediaIds\":[" + mediaId + "]}");

        DecisionResult result = service.decide(user, aid, Decision.REGENERATE, List.of(), null);

        assertThat(result.runId()).isNotNull();
        assertThat(assetStatus(aid)).isEqualTo("ARCHIVED");
        List<GenerationJob> newJobs = jobs.findByRun(tenantId, result.runId());
        assertThat(newJobs).hasSize(1);
        GenerationJob cutout = newJobs.get(0);
        assertThat(cutout.step()).isEqualTo(JobStep.CUTOUT);
        assertThat(cutout.variant()).isEqualTo("P2");
        JsonNode downstream = cutout.input().path("downstream");
        assertThat(downstream.size()).isEqualTo(1);
        assertThat(downstream.get(0).path("step").asString()).isEqualTo("WHITE_ANGLE");
        assertThat(downstream.get(0).path("variant").asString()).isEqualTo("P2");
        assertThat(cutout.input().path("inputs").path("image").asString())
                .isEqualTo("t" + tenantId + "/source-media/" + pid + "/P2.png");

        // Run the new cutout to completion: only the angle page regenerates.
        drainJobs();
        assertThat(assetStatus(aid)).isEqualTo("ARCHIVED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from asset where product_id = ? and spec_code = 'PAGE_ANGLE' "
                        + "and status = 'IN_REVIEW'", Integer.class, pid)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select max(version) from asset where product_id = ? and spec_code = 'PAGE_ANGLE'",
                Integer.class, pid)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from asset where product_id = ? and spec_code = 'PAGE_SCENE'",
                Integer.class, pid)).isZero();
        assertThat(jobs.findByRun(tenantId, result.runId()))
                .extracting(GenerationJob::step)
                .containsExactly(JobStep.CUTOUT, JobStep.WHITE_ANGLE);
    }

    @Test
    void approveRemaining_onlyInReviewOfProduct() {
        long productA = product("MG-A", "Kettle A");
        long productB = product("MG-B", "Kettle B");
        long a1 = asset(productA, "PAGE_MAIN", "main", 1, "IN_REVIEW", "[]", "{}");
        long a2 = asset(productA, "PAGE_ANGLE", "P2", 1, "IN_REVIEW", "[]", "{}");
        asset(productA, "PAGE_INBOX", "P8", 1, "APPROVED", "[]", "{}");
        long b1 = asset(productB, "PAGE_MAIN", "main", 1, "IN_REVIEW", "[]", "{}");

        int approved = service.approveRemaining(user, productA);

        assertThat(approved).isEqualTo(2);
        assertThat(assetStatus(a1)).isEqualTo("APPROVED");
        assertThat(assetStatus(a2)).isEqualTo("APPROVED");
        assertThat(assetStatus(b1)).isEqualTo("IN_REVIEW");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from asset_review where decision = 'APPROVE'", Integer.class))
                .isEqualTo(2);
    }

    /** Leases, uploads fake outputs, completes and fans out until the queue is empty. */
    private void drainJobs() {
        GenerationJob job;
        while ((job = jobs.lease("review-worker",
                Set.of(ExecutorType.COMFYUI, ExecutorType.COMPOSITE)).orElse(null)) != null) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : job.input().path("outputs").properties()) {
                String objectKey = entry.getValue().asString();
                byte[] bytes = ImageCodec.png("mask".equals(entry.getKey())
                        ? productMask() : whitePage());
                storage.put(objectKey, bytes, "image/png");
                outputs.put(entry.getKey(), file(objectKey));
            }
            completeLeased(job, outputs);
        }
    }

    private void completeLeased(GenerationJob job, Map<String, Object> outputs) {
        GenerationJob completed = jobs.complete(job.id(), "review-worker",
                Map.of("outputs", outputs), 0.0).orElseThrow();
        handler.onSucceeded(completed);
        runs.refreshStatus(completed.runId());
    }

    private static Map<String, Object> file(String objectKey) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("objectKey", objectKey);
        file.put("width", 1600);
        file.put("height", 1600);
        file.put("durationS", null);
        file.put("sha256", "0".repeat(64));
        return file;
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

    private String assetStatus(long assetId) {
        return jdbcTemplate.queryForObject("select status from asset where id = ?", String.class,
                assetId);
    }

    private int reviewCount(long assetId) {
        return jdbcTemplate.queryForObject("select count(*) from asset_review where asset_id = ?",
                Integer.class, assetId);
    }

    private long product(String sku, String name) {
        return jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, ?, 'simple', ?, ?, 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), sku, name);
    }

    private long media(long productId, String shotCode, String status) {
        return jdbcTemplate.queryForObject(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, thumb_object_key, content_type, size_bytes, sha256, qc_json, status) "
                        + "values (?, ?, ?, 'PHOTO', ?, ?, null, 'image/jpeg', 1024, ?, '{}', ?) returning id",
                Long.class, tenantId, productId, shotCode, "MG-X_" + shotCode + ".jpg",
                "t" + tenantId + "/source-media/" + productId + "/" + shotCode + ".png",
                shotCode.hashCode() + "0".repeat(58), status);
    }

    private long asset(long productId, String specCode, String variant, int version, String status,
            String flagsJson, String provenanceJson) {
        String prefix = "t" + tenantId + "/assets/" + productId + "/" + specCode + "/" + variant
                + "/v" + version;
        String sku = jdbcTemplate.queryForObject("select sku from product where id = ?", String.class,
                productId);
        String angle = switch (specCode) {
            case "PAGE_MAIN" -> "page-main";
            case "PAGE_INBOX" -> "page-inbox";
            default -> "page-" + variant.toLowerCase();
        };
        String type = "PAGE_SCENE".equals(specCode) ? "mixed" : "real";
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "object_key, thumb_object_key, width, height, status, precheck_json, "
                        + "provenance_json, file_name) "
                        + "values (?, ?, ?, ?, ?, 'IMAGE', ?, ?, 1600, 1600, ?, "
                        + "'{\"flags\":" + flagsJson + ",\"metrics\":{\"occupancy\":0.82}}'::jsonb, "
                        + "?::jsonb, ?) returning id",
                Long.class, tenantId, productId, specCode, variant, version,
                prefix + ".jpg", prefix + "_thumb.jpg", status, provenanceJson,
                AssetFileName.format(sku, angle, type, 1600, 1600, version, "jpg"));
    }
}
