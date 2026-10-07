package com.kiano.content.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobStep;
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
 * ExecutorAvailabilityMonitor: with no recent worker heartbeat every
 * executor's queued jobs wait; a worker reporting the capability releases
 * them again. Runs checkOnce() directly (scheduling disabled in tests).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ExecutorAvailabilityMonitorTest {

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private GenerationRunStore runs;

    @Autowired
    private WorkerStatusStore workerStatus;

    @Autowired
    private ExecutorAvailabilityMonitor monitor;

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
        productId = insertProduct("MONITOR-" + System.nanoTime());
        runId = runs.create(tenantId, productId, null);
    }

    @Test
    void noRecentWorker_marksQueuedComfyJobsWaiting() {
        long comfyId = createJob(JobStep.CUTOUT, "P1");
        long compositeId = createJob(JobStep.WHITE_MAIN, "main");
        monitor.checkOnce();
        assertThat(status(comfyId)).isEqualTo(JobStatus.WAITING_EXECUTOR);
        assertThat(status(compositeId)).isEqualTo(JobStatus.WAITING_EXECUTOR);
    }

    @Test
    void workerSeenWithCapability_releases() {
        long comfyId = createJob(JobStep.CUTOUT, "P1");
        jobs.markWaiting(ExecutorType.COMFYUI);
        jobs.markWaiting(ExecutorType.COMPOSITE);
        workerStatus.touch("w-1", Set.of(ExecutorType.COMFYUI), Set.of());
        monitor.checkOnce();
        assertThat(status(comfyId)).isEqualTo(JobStatus.QUEUED);
        // COMPOSITE has no reporting worker: its jobs keep waiting.
    }

    private JobStatus status(long jobId) {
        return jobs.find(tenantId, jobId).orElseThrow().status();
    }

    private long createJob(JobStep step, String variant) {
        return jobs.create(tenantId, productId, runId, step, variant,
                Map.of("inputs", Map.of("image", "monitor/in.png"),
                        "outputs", Map.of("cutout", "monitor/out.png")),
                List.of());
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
                        + "values (?, ?, ?, 'simple', ?, 'Monitor test', 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), sku);
    }
}
