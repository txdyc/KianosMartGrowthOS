package com.kiano.content.video.h3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.content.generation.JobStatus;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobStep;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H3 local watchdog (C5 T8b, Review Focus 3): H3_CLIP_LOCAL jobs stuck past
 * local-max-wait are cancelled and escalated once, whether they wait for a
 * worker (QUEUED / WAITING_EXECUTOR) or are held by one (LEASED). A cancelled
 * job's late worker report is dropped as lease-lost, so no second version is
 * produced; fresh jobs are left alone.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class H3LocalWatchdogTest {

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private GenerationRunStore runs;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RecordingCoordinator coordinator = new RecordingCoordinator();

    private H3Properties properties;
    private H3LocalWatchdog watchdog;
    private long tenantId;
    private long productId;
    private long runId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        productId = insertProduct("H3-WATCHDOG-" + System.nanoTime());
        runId = runs.create(tenantId, productId, null);
        coordinator.reset();
        properties = new H3Properties();
        properties.getLocal().setLocalMaxWait(Duration.ofHours(2));
        watchdog = new H3LocalWatchdog(jobs, properties, providerOf(coordinator));
    }

    @Test
    void waitingLocalJobPastMaxWait_cancelledAndEscalated() {
        long jobId = createLocalJob();
        ageJob(jobId);
        jdbcTemplate.update("update generation_job set status = 'WAITING_EXECUTOR' where id = ?", jobId);

        watchdog.sweep();

        GenerationJob job = jobs.find(tenantId, jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.error()).startsWith("H3_LOCAL_TIMEOUT");
        assertThat(coordinator.escalated).containsExactly("H3_LOCAL_TIMEOUT");

        // a second sweep must not escalate the same job twice
        watchdog.sweep();
        assertThat(coordinator.escalated).containsExactly("H3_LOCAL_TIMEOUT");
    }

    @Test
    void cancelledLeasedJob_lateWorkerCompletion_discarded_noSecondAsset() {
        long jobId = createLocalJob();
        ageJob(jobId);
        assertThat(jobs.lease("worker-1", Set.of(ExecutorType.COMFYUI))).isPresent();

        watchdog.sweep();

        assertThat(jobs.find(tenantId, jobId).orElseThrow().status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(coordinator.escalated).containsExactly("H3_LOCAL_TIMEOUT");

        // the worker's late report is lease-lost and changes nothing
        assertThat(jobs.complete(jobId, "worker-1", Map.of("outputs", Map.of()), 12.0)).isEmpty();
        assertThat(jobs.find(tenantId, jobId).orElseThrow().status()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void jobWithinMaxWait_untouched() {
        long jobId = createLocalJob();

        watchdog.sweep();

        assertThat(jobs.find(tenantId, jobId).orElseThrow().status()).isEqualTo(JobStatus.QUEUED);
        assertThat(coordinator.escalated).isEmpty();
    }

    private long createLocalJob() {
        return jobs.create(tenantId, productId, runId, JobStep.H3_CLIP_LOCAL, "demo",
                Map.of("workflow", Map.of("code", "H3_I2V"),
                        "outputs", Map.of("clip", "t1/gen/h3.mp4")), List.of());
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
                        + "values (?, ?, ?, 'simple', ?, 'H3 watchdog test', 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), sku);
    }

    private void ageJob(long jobId) {
        jdbcTemplate.update("update generation_job set created_at = now() - interval '3 hours' "
                + "where id = ?", jobId);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(bean));
        return provider;
    }

    private static final class RecordingCoordinator implements H3TerminalHandler {

        private final List<String> escalated = new ArrayList<>();

        private void reset() {
            escalated.clear();
        }

        @Override
        public void onH3Succeeded(GenerationJob job) {
        }

        @Override
        public void onH3Escalate(GenerationJob job, String reason) {
            escalated.add(reason);
        }
    }
}