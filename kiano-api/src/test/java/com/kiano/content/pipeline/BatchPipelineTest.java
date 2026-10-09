package com.kiano.content.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.asset.ReviewService;
import com.kiano.content.batch.BatchService;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobStep;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Batch pipelines (C6 Task 2, Review Focus 1): the cutouts of a batch start
 * with priority 10, the batch id and the night flag, every downstream job
 * inherits them, and a review regenerate stays interactive even while the
 * product sits in a running batch.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BatchPipelineTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private PipelineService pipeline;

    @Autowired
    private PipelineCompletionHandler handler;

    @Autowired
    private BatchService batches;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;
    private long storeId;
    private long userId;
    private CurrentUser user;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from content_batch_item");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from content_batch");
        jdbcTemplate.update("delete from publication");
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from comfy_workflow");
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from store_policy");
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
                        + "values (?, 'batch-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        user = new CurrentUser(userId, tenantId, Role.OPERATOR, "batch-op@example.test");
        activateWorkflow("CUTOUT");
        activateWorkflow("SCENE");
    }

    /** Batch rows reference products, runs and users: never leave them behind. */
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from content_batch_item");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from content_batch");
    }

    @Test
    void startForBatch_cutoutsCarryPriorityBatchAndNightFlag() {
        long pid = standardProduct("MG-STD1");
        for (String code : List.of("P1", "P2", "P4", "P6", "P8")) {
            media(pid, code, "ACCEPTED");
        }
        long batchId = batches.create(user, "Spring", List.of(pid), true).id();

        long runId = pipeline.startForBatch(tenantId, pid, batchId, true);

        List<GenerationJob> cutouts = jobs.findByRun(tenantId, runId);
        assertThat(cutouts).isNotEmpty();
        assertThat(cutouts).allSatisfy(job -> {
            assertThat(job.step()).isEqualTo(JobStep.CUTOUT);
            assertThat(job.priority()).isEqualTo(10);
            assertThat(job.batchId()).isEqualTo(batchId);
            assertThat(job.nightOnly()).isTrue();
        });
        // the run has no created_by and the audit says SYSTEM/BATCH
        assertThat(jdbcTemplate.queryForObject(
                "select created_by from generation_run where id = ?", Long.class, runId)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where tenant_id = ? and action = 'IMAGE_PIPELINE_STARTED' "
                        + "and actor_type = 'SYSTEM' and source = 'BATCH'", Integer.class, tenantId))
                .isEqualTo(1);
    }

    @Test
    void downstreamWhiteAngleSceneJobs_inheritParentPriority() {
        long pid = standardProduct("MG-STD1");
        for (String code : List.of("P1", "P2")) {
            media(pid, code, "ACCEPTED");
        }
        long batchId = batches.create(user, "Spring", List.of(pid), true).id();
        long runId = pipeline.startForBatch(tenantId, pid, batchId, true);
        GenerationJob cutout = jobs.findByRun(tenantId, runId).stream()
                .filter(job -> "P2".equals(job.input().path("shotCode").asString()))
                .findFirst().orElseThrow();

        completeCutout(cutout);

        List<GenerationJob> downstream = jobs.findByRun(tenantId, runId).stream()
                .filter(job -> job.id() != cutout.id()).toList();
        assertThat(downstream).isNotEmpty();
        assertThat(downstream).allSatisfy(job -> {
            assertThat(job.priority()).isEqualTo(10);
            assertThat(job.batchId()).isEqualTo(batchId);
            assertThat(job.nightOnly()).isTrue();
        });

        // the scene job created from the scene input inherits it as well
        GenerationJob sceneInput = downstream.stream()
                .filter(job -> job.step() == JobStep.SCENE_INPUT).findFirst().orElseThrow();
        completeCutout(sceneInput);
        GenerationJob scene = jobs.findByRun(tenantId, runId).stream()
                .filter(job -> job.step() == JobStep.SCENE).findFirst().orElseThrow();
        assertThat(scene.priority()).isEqualTo(10);
        assertThat(scene.batchId()).isEqualTo(batchId);
        assertThat(scene.nightOnly()).isTrue();
    }

    @Test
    void reviewRegenerate_createsInteractiveJob() {
        long pid = standardProduct("MG-STD1");
        long batchId = batches.create(user, "Spring", List.of(pid), false).id();
        long mediaId = media(pid, "P1", "ACCEPTED");
        long assetId = jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json) "
                        + "values (?, ?, 'PAGE_MAIN', 'main', 1, 'IMAGE', 'IN_REVIEW', "
                        + "'{}'::jsonb, ?::jsonb) returning id",
                Long.class, tenantId, pid,
                "{\"sourceMediaIds\":[" + mediaId + "],\"composer\":{\"canvas\":1600}}");

        ReviewService.DecisionResult result = reviewService.decide(user, assetId,
                ReviewService.Decision.REGENERATE, List.of(), null);

        assertThat(result.runId()).isNotNull();
        List<GenerationJob> created = jobs.findByRun(tenantId, result.runId());
        assertThat(created).isNotEmpty();
        assertThat(created).allSatisfy(job -> {
            assertThat(job.priority()).isZero();
            assertThat(job.batchId()).isNull();
            assertThat(job.nightOnly()).isFalse();
        });
        assertThat(batchId).isPositive();
    }

    // ---- helpers ----

    private void completeCutout(GenerationJob job) {
        // lease until this exact job is held: other cutouts of the run may be older
        Optional<GenerationJob> leased = jobs.lease("worker-1",
                Set.of(ExecutorType.COMFYUI, ExecutorType.COMPOSITE), true);
        while (leased.isPresent() && leased.get().id() != job.id()) {
            leased = jobs.lease("worker-1",
                    Set.of(ExecutorType.COMFYUI, ExecutorType.COMPOSITE), true);
        }
        assertThat(leased).isPresent();
        GenerationJob completed = jobs.complete(job.id(), "worker-1",
                Map.of("outputs", Map.of("cutout", Map.of("objectKey", "t1/gen/x/cutout.png"),
                        "image", Map.of("objectKey", "t1/gen/x/image.png"),
                        "mask", Map.of("objectKey", "t1/gen/x/mask.png"))), 1.0).orElseThrow();
        handler.onSucceeded(completed);
    }

    private void activateWorkflow(String code) {
        jdbcTemplate.update(
                "insert into comfy_workflow (tenant_id, code, version, workflow_json, manifest_json, "
                        + "model_refs, status) values (?, ?, 1, '{}', ?, '[]', 'APPROVED')",
                tenantId, code, "{\"code\":\"" + code + "\"}");
    }

    private long standardProduct(String sku) {
        return jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, ?, 'simple', ?, ?, 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), sku, "Product " + sku);
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
}