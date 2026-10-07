package com.kiano.content.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobStep;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class GenerationJobStoreTest {

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private GenerationRunStore runs;

    @Autowired
    private WorkerStatusStore workerStatus;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantId;
    private long productId;
    private long runId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from worker_status");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'", Long.class);
        productId = insertProduct("GEN-STORE-" + System.nanoTime());
        runId = runs.create(tenantId, productId, null);
    }

    private long insertProduct(String sku) {
        Long storeId = jdbcTemplate.query(
                "select id from store where tenant_id = ? order by id limit 1",
                (rs, rowNum) -> rs.getLong("id"), tenantId).stream().findFirst().orElseGet(
                        () -> jdbcTemplate.queryForObject(
                                "insert into store (tenant_id, platform, base_url) "
                                        + "values (?, 'woocommerce', 'http://woo.test') returning id",
                                Long.class, tenantId));
        return jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, ?, 'simple', ?, 'Gen store test', 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), sku);
    }

    private long createJob(JobStep step, String variant) {
        return jobs.create(tenantId, productId, runId, step, variant,
                Map.of("inputs", Map.of("image", "t1/gen/x.png"), "outputs", Map.of("cutout", "t1/gen/y.png")),
                List.of());
    }

    @Test
    void lease_onlyMatchingExecutor_andIncrementsAttempts() {
        long cutoutId = createJob(JobStep.CUTOUT, "P1");
        long whiteId = createJob(JobStep.WHITE_MAIN, "main");
        Optional<GenerationJob> leased = jobs.lease("worker-1", Set.of(ExecutorType.COMPOSITE));
        assertThat(leased).isPresent();
        assertThat(leased.get().id()).isEqualTo(whiteId);
        assertThat(leased.get().status()).isEqualTo(JobStatus.LEASED);
        assertThat(leased.get().attempts()).isEqualTo(1);
        assertThat(leased.get().leaseOwner()).isEqualTo("worker-1");
        // COMFYUI job is not leasable with COMPOSITE-only capabilities.
        assertThat(jobs.lease("worker-1", Set.of(ExecutorType.COMPOSITE))).isEmpty();
        Optional<GenerationJob> comfy = jobs.lease("worker-2", Set.of(ExecutorType.COMFYUI));
        assertThat(comfy).isPresent();
        assertThat(comfy.get().id()).isEqualTo(cutoutId);
    }

    @Test
    void lease_concurrentWorkers_neverShareJob() throws Exception {
        for (int i = 0; i < 6; i++) {
            createJob(JobStep.CUTOUT, "P" + i);
        }
        ConcurrentLinkedQueue<Long> leased = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        Runnable leaser = () -> {
            try {
                start.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            Optional<GenerationJob> job;
            while ((job = jobs.lease("w-" + Thread.currentThread().getName(), Set.of(ExecutorType.COMFYUI)))
                    .isPresent()) {
                leased.add(job.get().id());
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(leaser);
        pool.submit(leaser);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(leased).hasSize(6);
        assertThat(leased.stream().distinct().count()).isEqualTo(6);
    }

    @Test
    void heartbeat_wrongWorker_false_andDoesNotExtend() {
        long jobId = createJob(JobStep.CUTOUT, "P1");
        jobs.lease("worker-1", Set.of(ExecutorType.COMFYUI));
        assertThat(jobs.heartbeat(jobId, "worker-2")).isFalse();
        Instant expiry = jdbcTemplate.queryForObject(
                "select lease_expires_at from generation_job where id = ?", Timestamp.class, jobId).toInstant();
        assertThat(expiry).isAfter(Instant.now().plus(Duration.ofMinutes(9)));
        assertThat(jobs.heartbeat(jobId, "worker-1")).isTrue();
    }

    @Test
    void complete_storesOutputAndGpuSeconds() {
        long jobId = createJob(JobStep.CUTOUT, "P1");
        jobs.lease("worker-1", Set.of(ExecutorType.COMFYUI));
        Optional<GenerationJob> done = jobs.complete(jobId, "worker-1",
                Map.of("outputs", Map.of("cutout", Map.of("sha256", "abc"))), 12.5);
        assertThat(done).isPresent();
        assertThat(done.get().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.get().gpuSeconds()).isEqualTo(12.5);
        assertThat(done.get().output().get("outputs").get("cutout").get("sha256").asString()).isEqualTo("abc");
        assertThat(done.get().finishedAt()).isNotNull();
        assertThat(done.get().leaseOwner()).isNull();
    }

    @Test
    void complete_afterLeaseReaped_returnsEmpty() {
        long jobId = createJob(JobStep.CUTOUT, "P1");
        jobs.lease("worker-1", Set.of(ExecutorType.COMFYUI));
        jdbcTemplate.update("update generation_job set lease_expires_at = now() - interval '1 second' where id = ?",
                jobId);
        assertThat(jobs.reapExpiredLeases()).isEqualTo(1);
        assertThat(jobs.complete(jobId, "worker-1", Map.of(), 0.0)).isEmpty();
        GenerationJob job = jobs.find(tenantId, jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void fail_executorUnavailable_waitingAndRefundsAttempt() {
        long jobId = createJob(JobStep.CUTOUT, "P1");
        jobs.lease("worker-1", Set.of(ExecutorType.COMFYUI));
        Optional<GenerationJob> failed = jobs.fail(jobId, "worker-1", "ComfyUI unreachable", true, true);
        assertThat(failed).isPresent();
        assertThat(failed.get().status()).isEqualTo(JobStatus.WAITING_EXECUTOR);
        assertThat(failed.get().attempts()).isZero();
        assertThat(failed.get().leaseOwner()).isNull();
        assertThat(failed.get().error()).contains("ComfyUI unreachable");
    }

    @Test
    void fail_retryable_backoff_thenFailedAtMax() {
        long jobId = createJob(JobStep.CUTOUT, "P1");
        for (int round = 1; round <= 3; round++) {
            jobs.lease("worker-1", Set.of(ExecutorType.COMFYUI));
            Optional<GenerationJob> failed = jobs.fail(jobId, "worker-1", "boom", true, false);
            assertThat(failed).isPresent();
            if (round < 3) {
                assertThat(failed.get().status()).isEqualTo(JobStatus.QUEUED);
                assertThat(failed.get().attempts()).isEqualTo(round);
                Instant runAfter = jdbcTemplate.queryForObject(
                        "select run_after from generation_job where id = ?", Timestamp.class, jobId).toInstant();
                long backoffSeconds = Duration.between(Instant.now(), runAfter).toSeconds();
                assertThat(backoffSeconds).isBetween(30L * (1L << (round - 1)) - 5, 30L * (1L << (round - 1)) + 5);
                jdbcTemplate.update("update generation_job set run_after = now() where id = ?", jobId);
            } else {
                assertThat(failed.get().status()).isEqualTo(JobStatus.FAILED);
                assertThat(failed.get().attempts()).isEqualTo(3);
                assertThat(failed.get().finishedAt()).isNotNull();
            }
        }
    }

    @Test
    void fail_nonRetryable_failedImmediately() {
        long jobId = createJob(JobStep.CUTOUT, "P1");
        jobs.lease("worker-1", Set.of(ExecutorType.COMFYUI));
        Optional<GenerationJob> failed = jobs.fail(jobId, "worker-1", "CUTOUT_EMPTY: nothing left", false, false);
        assertThat(failed).isPresent();
        assertThat(failed.get().status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.get().attempts()).isEqualTo(1);
        assertThat(failed.get().error()).startsWith("CUTOUT_EMPTY");
    }

    @Test
    void reap_expiredLease_requeues() {
        long jobId = createJob(JobStep.CUTOUT, "P1");
        jobs.lease("worker-1", Set.of(ExecutorType.COMFYUI));
        jdbcTemplate.update("update generation_job set lease_expires_at = now() - interval '1 second' where id = ?",
                jobId);
        assertThat(jobs.reapExpiredLeases()).isEqualTo(1);
        GenerationJob job = jobs.find(tenantId, jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.leaseOwner()).isNull();

        // Exhausted attempts on expiry -> FAILED.
        jobs.lease("worker-1", Set.of(ExecutorType.COMFYUI));
        jobs.lease("worker-1", Set.of(ExecutorType.COMFYUI));
        // the second lease above re-leased after the reap; expire and reap until FAILED
        jdbcTemplate.update("update generation_job set lease_expires_at = now() - interval '1 second', "
                + "attempts = 3 where id = ?", jobId);
        jobs.reapExpiredLeases();
        assertThat(jobs.find(tenantId, jobId).orElseThrow().status()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void markWaiting_thenRelease_roundTrip() {
        long comfyJob = createJob(JobStep.CUTOUT, "P1");
        long compositeJob = createJob(JobStep.WHITE_MAIN, "main");
        jobs.markWaiting(ExecutorType.COMFYUI);
        assertThat(jobs.find(tenantId, comfyJob).orElseThrow().status()).isEqualTo(JobStatus.WAITING_EXECUTOR);
        assertThat(jobs.find(tenantId, compositeJob).orElseThrow().status()).isEqualTo(JobStatus.QUEUED);
        assertThat(jobs.lease("worker-1", Set.of(ExecutorType.COMFYUI))).isEmpty();
        assertThat(jobs.lease("worker-1", Set.of(ExecutorType.COMPOSITE))).isPresent();

        assertThat(jobs.releaseWaiting(ExecutorType.COMFYUI)).isEqualTo(1);
        assertThat(jobs.find(tenantId, comfyJob).orElseThrow().status()).isEqualTo(JobStatus.QUEUED);
        assertThat(jobs.lease("worker-1", Set.of(ExecutorType.COMFYUI))).isPresent();
    }

    @Test
    void retry_onlyFromFailed() {
        long jobId = createJob(JobStep.CUTOUT, "P1");
        assertThatThrownBy(() -> jobs.retry(tenantId, jobId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("JOB_NOT_FAILED"));
        jobs.lease("worker-1", Set.of(ExecutorType.COMFYUI));
        jobs.fail(jobId, "worker-1", "boom", false, false);
        jdbcTemplate.update("update generation_job set attempts = 3 where id = ?", jobId);
        GenerationJob retried = jobs.retry(tenantId, jobId);
        assertThat(retried.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(retried.attempts()).isZero();
        assertThat(retried.error()).isNull();
        assertThat(retried.finishedAt()).isNull();
    }

    @Test
    void runStatus_doneOrPartialWhenAllTerminal() {
        long jobId1 = createJob(JobStep.CUTOUT, "P1");
        long jobId2 = createJob(JobStep.WHITE_MAIN, "main");
        runs.refreshStatus(runId);
        assertThat(runs.latest(tenantId, productId).orElseThrow().status()).isEqualTo("RUNNING");

        jobs.lease("w", Set.of(ExecutorType.COMFYUI, ExecutorType.COMPOSITE));
        jobs.lease("w", Set.of(ExecutorType.COMFYUI, ExecutorType.COMPOSITE));
        jobs.complete(jobId1, "w", Map.of(), 1.0);
        runs.refreshStatus(runId);
        assertThat(runs.latest(tenantId, productId).orElseThrow().status()).isEqualTo("RUNNING");

        jobs.fail(jobId2, "w", "boom", false, false);
        runs.refreshStatus(runId);
        GenerationRunStore.GenerationRun run = runs.latest(tenantId, productId).orElseThrow();
        assertThat(run.status()).isEqualTo("PARTIAL");
        assertThat(run.finishedAt()).isNotNull();

        // Retry and succeed -> DONE.
        jobs.retry(tenantId, jobId2);
        jobs.lease("w", Set.of(ExecutorType.COMPOSITE));
        jobs.complete(jobId2, "w", Map.of(), 1.0);
        runs.refreshStatus(runId);
        assertThat(runs.latest(tenantId, productId).orElseThrow().status()).isEqualTo("DONE");
    }

    @Test
    void workerStatus_availability_window() {
        workerStatus.touch("w1", Set.of(ExecutorType.COMPOSITE), Set.of());
        assertThat(workerStatus.isAvailable(ExecutorType.COMPOSITE, Duration.ofSeconds(90))).isTrue();

        // Stale worker (last seen 2 minutes ago) alone does not make COMFYUI available.
        jdbcTemplate.update(
                "insert into worker_status (worker_id, last_seen_at, capabilities, unavailable) "
                        + "values ('w-stale', now() - interval '2 minutes', '[\"COMFYUI\"]'::jsonb, '[]'::jsonb)");
        assertThat(workerStatus.isAvailable(ExecutorType.COMFYUI, Duration.ofSeconds(90))).isFalse();

        // Available worker that later reports COMPOSITE as unavailable no longer counts.
        workerStatus.touch("w1", Set.of(ExecutorType.COMPOSITE), Set.of(ExecutorType.COMPOSITE));
        assertThat(workerStatus.isAvailable(ExecutorType.COMPOSITE, Duration.ofSeconds(90))).isFalse();

        assertThat(workerStatus.list()).extracting(WorkerStatusStore.WorkerStatusView::workerId)
                .containsExactlyInAnyOrder("w1", "w-stale");
    }
}
