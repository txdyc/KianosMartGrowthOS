package com.kiano.content.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.batch.BatchService.BatchView;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobStep;
import java.util.List;
import java.util.Map;
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
 * BatchService (C6 Task 2): creation only accepts top-level STANDARD SKUs that
 * are not already in a running batch, and pause/resume/cancel follow the batch
 * state machine - cancelling stops the batch's not-yet-leased jobs while
 * everything already produced stays.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BatchServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BatchService batches;

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private GenerationRunStore runs;

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
        jdbcTemplate.update("delete from product_fact_sheet");
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
                        + "values (?, 'batch-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        user = new CurrentUser(userId, tenantId, Role.OPERATOR, "batch-op@example.test");
    }

    /**
     * Batch rows reference products, runs and users: leaving them behind would
     * break every other test class that cleans up those tables.
     */
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from content_batch_item");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from content_batch");
    }

    @Test
    void create_rejectsHeroAndVariations_listsOffenders() {
        long standard = product("MG-STD1", null);
        long variation = product("MG-VAR1", standard);
        long hero = product("MG-HERO1", null);
        jdbcTemplate.update("insert into product_profile (product_id, tenant_id, content_tier) "
                + "values (?, ?, 'HERO')", hero, tenantId);

        assertThatThrownBy(() -> batches.create(user, "Mixed", List.of(standard, variation), false))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("NOT_TOP_LEVEL");
                    assertThat(ex.getDetails().get("productIds").toString())
                            .contains(String.valueOf(variation));
                });
        assertThatThrownBy(() -> batches.create(user, "With hero", List.of(standard, hero), false))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("BATCH_HERO_NOT_ALLOWED");
                    assertThat(ex.getDetails().get("productIds").toString())
                            .contains(String.valueOf(hero));
                });
        // nothing was created by the rejected attempts
        assertThat(batches.list(tenantId)).isEmpty();
    }

    @Test
    void create_productInAnotherActiveBatch_409() {
        long pid = product("MG-STD1", null);
        BatchView first = batches.create(user, "First", List.of(pid), true);

        assertThatThrownBy(() -> batches.create(user, "Second", List.of(pid), false))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.getCode()).isEqualTo("PRODUCT_IN_ACTIVE_BATCH");
                });
        assertThat(first.nightOnly()).isTrue();

        BatchService.Candidate candidate = batches.candidates(tenantId).stream()
                .filter(row -> row.productId() == pid).findFirst().orElseThrow();
        assertThat(candidate.activeBatchId()).isEqualTo(first.id());

        // cancelling the batch frees the SKU for a new one
        batches.cancel(user, first.id());
        BatchView second = batches.create(user, "Second", List.of(pid), false);
        assertThat(second.id()).isEqualTo(first.id() + 1);
        assertThat(batches.candidates(tenantId).stream()
                .filter(row -> row.productId() == pid).findFirst().orElseThrow().activeBatchId())
                .isEqualTo(second.id());
    }

    @Test
    void create_duplicateProductIds_422InsteadOf500() {
        long pid = product("MG-STD1", null);

        assertThatThrownBy(() -> batches.create(user, "Spring", List.of(pid, pid), false))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("VALIDATION_FAILED");
                    assertThat(ex.getDetails()).containsEntry("duplicates", List.of(pid));
                });
    }

    @Test
    void concurrentCreates_neverPutOneProductInTwoActiveBatches() throws Exception {
        long pid = product("MG-STD1", null);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Object>> futures = new java.util.ArrayList<>();
        try {
            for (String name : List.of("A", "B")) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        return batches.create(user, name, List.of(pid), false);
                    } catch (ApiException ex) {
                        return ex.getCode();
                    }
                }));
            }
            go.countDown();
            List<Object> outcomes = new java.util.ArrayList<>();
            for (var future : futures) {
                outcomes.add(future.get());
            }
            assertThat(outcomes).filteredOn(o -> o instanceof BatchView).hasSize(1);
            assertThat(outcomes).contains("PRODUCT_IN_ACTIVE_BATCH");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from content_batch_item "
                + "where product_id = ?", Integer.class, pid)).isEqualTo(1);
    }

    @Test
    void cancel_cancelsOnlyUnleasedBatchJobs_keepsAssets() {
        long pid = product("MG-STD1", null);
        BatchView batch = batches.create(user, "Spring", List.of(pid), false);
        long runId = runs.create(tenantId, pid, null);

        // a job a worker already holds: cancel must leave it alone
        long leased = jobs.create(tenantId, pid, runId, JobStep.CUTOUT, "P1", Map.of(), List.of(),
                GenerationJobStore.JobPriority.batch(batch.id(), false));
        jobs.lease("worker-1", Set.of(ExecutorType.COMFYUI), true);
        // a job waiting for its executor is still "not leased"
        long waiting = jobs.create(tenantId, pid, runId, JobStep.CUTOUT, "P2", Map.of(), List.of(),
                GenerationJobStore.JobPriority.batch(batch.id(), false));
        jobs.markWaiting(ExecutorType.COMFYUI);
        // queued batch work and interactive work
        long queued = jobs.create(tenantId, pid, runId, JobStep.CUTOUT, "P4", Map.of(), List.of(),
                GenerationJobStore.JobPriority.batch(batch.id(), false));
        long interactive = jobs.create(tenantId, pid, runId, JobStep.CUTOUT, "P6", Map.of(),
                List.of(), GenerationJobStore.JobPriority.INTERACTIVE);
        long assetId = jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json) "
                        + "values (?, ?, 'PAGE_MAIN', 'main', 1, 'IMAGE', 'APPROVED', "
                        + "'{}'::jsonb, '{}'::jsonb) returning id",
                Long.class, tenantId, pid);

        BatchView cancelled = batches.cancel(user, batch.id());

        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.finishedAt()).isNotNull();
        assertThat(statusOf(queued)).isEqualTo("CANCELLED");
        assertThat(statusOf(waiting)).isEqualTo("CANCELLED");
        assertThat(statusOf(leased)).isEqualTo("LEASED");
        assertThat(statusOf(interactive)).isEqualTo("QUEUED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from asset where id = ?",
                Integer.class, assetId)).isEqualTo(1);
    }

    @Test
    void pauseResume_transitions_andInvalidTransition409() {
        long pid = product("MG-STD1", null);
        BatchView batch = batches.create(user, "Spring", List.of(pid), false);

        assertThat(batches.pause(user, batch.id()).status()).isEqualTo("PAUSED");
        assertThatThrownBy(() -> batches.pause(user, batch.id()))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getCode()).isEqualTo("BATCH_STATE"));
        assertThat(batches.resume(user, batch.id()).status()).isEqualTo("RUNNING");

        batches.cancel(user, batch.id());
        assertThatThrownBy(() -> batches.resume(user, batch.id()))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus().value()).isEqualTo(409));
    }

    private String statusOf(long jobId) {
        return jdbcTemplate.queryForObject("select status from generation_job where id = ?",
                String.class, jobId);
    }

    /** A standard top-level product; {@code parentId} makes it a variation. */
    private long product(String sku, Long parentId) {
        return jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, parent_id, type, sku, name, "
                        + "status, synced_at) values (?, ?, ?, ?, ?, ?, ?, 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), parentId,
                parentId == null ? "simple" : "variation", sku, "Product " + sku);
    }
}