package com.kiano.content.video.h3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kiano.TestcontainersConfiguration;
import com.kiano.commerce.ProductCatalog;
import com.kiano.content.batch.BatchProperties;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.content.generation.JobFailureListener;
import com.kiano.content.generation.JobStatus;
import com.kiano.content.template.TemplateBootstrap;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobStep;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
 * H3JobRunner (C5 T8a) against the real generation_job store and object
 * storage, with the provider replaced by a stub: submitting parks the job
 * without consuming its attempt, a restarted api resumes from external_ref,
 * waits past max-wait fail with H3_TIMEOUT, the daily budget blocks submission,
 * successes upload the clip and record the cost, and retryable failures back
 * off silently until they become final.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class H3JobRunnerTest {

    private static final String FIRST_FRAME_KEY = "t1/video-inputs/run/demo/h3_first.jpg";
    private static final String CLIP_KEY = "t1/video-inputs/run/demo/h3_clip.mp4";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4};
    private static final byte[] MP4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 9};

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private GenerationRunStore runs;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private H3PromptBuilder promptBuilder;

    @Autowired
    private ProductCatalog products;

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private TemplateBootstrap bootstrap;

    private final BatchProperties batchProperties = new BatchProperties();

    private final StubH3Client client = new StubH3Client();
    private final RecordingCoordinator coordinator = new RecordingCoordinator();

    private H3Properties properties;
    private H3JobRunner runner;
    private long tenantId;
    private long productId;
    private long runId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        bootstrap.bootstrapAll();
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        productId = insertProduct("H3-RUNNER-" + System.nanoTime());
        runId = runs.create(tenantId, productId, null);
        storage.put(FIRST_FRAME_KEY, JPEG, "image/jpeg");
        coordinator.reset();
        client.reset();

        properties = new H3Properties();
        properties.getApi().setEnabled(true);
        properties.getApi().setPricePerSecondUsd(new BigDecimal("2")); // 5 s → 10 USD
        properties.getApi().setDailyBudgetUsd(new BigDecimal("10"));
        properties.getApi().setPollInterval(Duration.ofSeconds(20));
        properties.getApi().setMaxWait(Duration.ofMinutes(15));
        runner = new H3JobRunner(jobs, storage, client, properties, promptBuilder, products, auditLog,
                batchProperties, providerOf(coordinator), providerOf(coordinator));
    }

    @Test
    void submitsThenParks_withoutConsumingAttempt() {
        long jobId = createApiJob(apiInput());

        runner.pollOnce();

        assertThat(client.submitted).hasSize(1);
        H3Request request = client.submitted.get(0);
        assertThat(request.durationSeconds()).isEqualTo(5);
        assertThat(request.resolution()).isEqualTo("1080P");
        assertThat(request.prompt()).startsWith(
                "For the target video, at 0.00 seconds into the target video,");
        assertThat(request.firstFrameJpeg()).isEqualTo(JPEG);

        GenerationJob job = jobs.find(tenantId, jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.externalRef()).isEqualTo("h3-task-1");
        assertThat(job.attempts()).isZero();
        assertThat(job.leaseOwner()).isNull();
        // parked for the poll interval: not leasable yet
        assertThat(jobs.lease(H3JobRunner.LEASE_OWNER, Set.of(ExecutorType.H3))).isEmpty();
        assertThat(auditCount("H3_CLIP_SUBMITTED", jobId)).isEqualTo(1);
    }

    @Test
    void apiRestartMidPoll_resumesFromExternalRef_noResubmit() {
        long jobId = createApiJob(apiInput());
        runner.pollOnce();
        assertThat(client.submitted).hasSize(1);

        // the api restarted: a fresh runner with a fresh client picks the parked job up
        StubH3Client restarted = new StubH3Client();
        restarted.pollResult = id -> new H3TaskStatus.Succeeded("h3-file-1");
        restarted.downloadContent = MP4;
        H3JobRunner second = new H3JobRunner(jobs, storage, restarted, properties, promptBuilder,
                products, auditLog, batchProperties, providerOf(coordinator), providerOf(coordinator));
        jdbcTemplate.update("update generation_job set run_after = now() where id = ?", jobId);

        second.pollOnce();

        assertThat(restarted.submitted).isEmpty();
        assertThat(restarted.polled).containsExactly("h3-task-1");
        assertThat(jobs.find(tenantId, jobId).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void timeoutAfterMaxWait_failsAndNotifiesCoordinator() {
        long jobId = createApiJob(apiInput());
        jdbcTemplate.update("update generation_job set external_ref = 'h3-task-1', "
                + "created_at = now() - interval '20 minutes' where id = ?", jobId);
        client.pollResult = id -> new H3TaskStatus.Pending();

        runner.pollOnce();

        GenerationJob job = jobs.find(tenantId, jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.error()).startsWith("H3_TIMEOUT");
        assertThat(client.submitted).isEmpty();
        assertThat(coordinator.failed).containsExactly(jobId);
    }

    @Test
    void budgetExceeded_failsWithoutSubmitting() {
        // an H3 clip finished earlier today already used 6 of the 10 USD budget
        long previous = createApiJob(apiInput());
        assertThat(jobs.lease("seed", Set.of(ExecutorType.H3))).isPresent();
        jobs.complete(previous, "seed", Map.of(), 0, new BigDecimal("6"));
        long jobId = createApiJob(apiInput());

        runner.pollOnce();

        assertThat(client.submitted).isEmpty();
        GenerationJob job = jobs.find(tenantId, jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.error()).startsWith("H3_BUDGET_EXCEEDED");
        assertThat(coordinator.failed).containsExactly(jobId);
    }

    @Test
    void succeeded_uploadsClip_recordsCost_notifiesCoordinator() {
        long jobId = createApiJob(apiInput());
        jdbcTemplate.update("update generation_job set external_ref = 'h3-task-1' where id = ?", jobId);
        client.pollResult = id -> new H3TaskStatus.Succeeded("h3-file-1");
        client.downloadContent = MP4;

        runner.pollOnce();

        assertThat(client.polled).containsExactly("h3-task-1");
        GenerationJob job = jobs.find(tenantId, jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.output().path("outputs").path("clip").path("objectKey").asString())
                .isEqualTo(CLIP_KEY);
        assertThat(job.output().path("outputs").path("clip").path("durationS").asInt()).isEqualTo(5);
        assertThat(storage.exists(CLIP_KEY)).isTrue();
        assertThat(storage.download(CLIP_KEY)).isEqualTo(MP4);
        assertThat(jdbcTemplate.queryForObject("select cost_usd from generation_job where id = ?",
                BigDecimal.class, jobId)).isEqualByComparingTo("10");
        assertThat(coordinator.succeeded).containsExactly(jobId);
        assertThat(coordinator.failed).isEmpty();
        assertThat(auditCount("H3_CLIP_FINISHED", jobId)).isEqualTo(1);
    }

    @Test
    void retryableFailure_backsOff_doesNotNotifyUntilFinal() {
        long jobId = createApiJob(apiInput());
        jdbcTemplate.update("update generation_job set external_ref = 'h3-task-1' where id = ?", jobId);
        client.pollError = new H3ClientException("H3 HTTP 503: upstream unavailable", true, false);

        runner.pollOnce();

        GenerationJob job = jobs.find(tenantId, jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED); // backed off, not final
        assertThat(job.error()).startsWith("H3_FAILED");
        assertThat(jobs.lease(H3JobRunner.LEASE_OWNER, Set.of(ExecutorType.H3))).isEmpty();
        assertThat(coordinator.failed).isEmpty();

        // attempts exhausted: the next failure is final and notifies exactly once
        jdbcTemplate.update("update generation_job set attempts = max_attempts, run_after = now() "
                + "where id = ?", jobId);
        runner.pollOnce();

        job = jobs.find(tenantId, jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(coordinator.failed).containsExactly(jobId);
    }

    @Test
    void budget_countsClipsSubmittedButNotYetFinished() {
        properties.getApi().setDailyBudgetUsd(new BigDecimal("20")); // room for two 10 USD clips
        long first = createApiJob(apiInput());
        long second = createApiJob(apiInput());
        long third = createApiJob(apiInput());

        runner.pollOnce(); // submits and parks the first two; nothing has completed yet
        runner.pollOnce(); // the third must see 20 USD already committed

        assertThat(client.submitted).hasSize(2);
        assertThat(jobs.find(tenantId, first).orElseThrow().externalRef()).isNotNull();
        assertThat(jobs.find(tenantId, second).orElseThrow().externalRef()).isNotNull();
        GenerationJob job = jobs.find(tenantId, third).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.error()).startsWith("H3_BUDGET_EXCEEDED");
    }

    @Test
    void apiDisabled_queuedAndParkedClipsFailOverInsteadOfStranding() {
        long queued = createApiJob(apiInput());
        long parked = createApiJob(apiInput());
        jdbcTemplate.update("update generation_job set external_ref = 'h3-task-9' where id = ?",
                parked);
        properties.getApi().setEnabled(false);

        runner.scheduled();

        for (long id : List.of(queued, parked)) {
            GenerationJob job = jobs.find(tenantId, id).orElseThrow();
            assertThat(job.status()).isEqualTo(JobStatus.FAILED);
            assertThat(job.error()).startsWith("H3_API_DISABLED");
        }
        assertThat(coordinator.failed).containsExactlyInAnyOrder(queued, parked);
        assertThat(client.submitted).isEmpty();
        assertThat(client.polled).isEmpty();
    }

    @Test
    void unexpectedErrorDuringSubmit_failsRetryably_insteadOfLeavingTheJobLeased() {
        Map<String, Object> input = new java.util.HashMap<>(apiInput());
        input.put("firstFrameKey", "t1/does/not/exist.jpg"); // storage throws, not H3ClientException
        long jobId = createApiJob(input);

        org.assertj.core.api.Assertions.assertThatCode(runner::pollOnce).doesNotThrowAnyException();

        GenerationJob job = jobs.find(tenantId, jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED); // backed off for a retry
        assertThat(job.leaseOwner()).isNull();
        assertThat(job.error()).startsWith("H3_FAILED");
        assertThat(client.submitted).isEmpty();
    }

    @Test
    void handlerErrorAfterCompletion_doesNotStopTheRound() {
        long first = createApiJob(apiInput());
        long second = createApiJob(apiInput());
        jdbcTemplate.update("update generation_job set external_ref = 'h3-task-1' "
                + "where id in (?, ?)", first, second);
        client.pollResult = id -> new H3TaskStatus.Succeeded("h3-file-1");
        client.downloadContent = MP4;
        coordinator.throwsOnSucceeded = 1;

        org.assertj.core.api.Assertions.assertThatCode(runner::pollOnce).doesNotThrowAnyException();

        assertThat(jobs.find(tenantId, first).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(jobs.find(tenantId, second).orElseThrow().status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(coordinator.succeeded).contains(second);
    }

    private long createApiJob(Map<String, Object> input) {
        return jobs.create(tenantId, productId, runId, JobStep.H3_CLIP_API, "demo", input, List.of());
    }

    private Map<String, Object> apiInput() {
        return Map.of(
                "type", "demo",
                "sceneLabel", "a clean studio backdrop",
                "firstFrameKey", FIRST_FRAME_KEY,
                "outputs", Map.of("clip", CLIP_KEY));
    }

    private int auditCount(String action, long jobId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = ? and target_id = ?",
                Integer.class, action, String.valueOf(jobId));
        return count == null ? 0 : count;
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
                        + "values (?, ?, ?, 'simple', ?, 'Morgan 1.7L Kettle', 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), sku);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        // a fresh stream per call: a round can fan out more than once
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(bean));
        return provider;
    }

    /** H3Client stub: records submissions/polls and plays canned outcomes. */
    private static final class StubH3Client implements H3Client {

        private final List<H3Request> submitted = new ArrayList<>();
        private final List<String> polled = new ArrayList<>();
        private Function<String, H3TaskStatus> pollResult = id -> new H3TaskStatus.Pending();
        private H3ClientException pollError;
        private byte[] downloadContent = new byte[0];

        private void reset() {
            submitted.clear();
            polled.clear();
            pollResult = id -> new H3TaskStatus.Pending();
            pollError = null;
            downloadContent = new byte[0];
        }

        @Override
        public String submit(H3Request request) {
            submitted.add(request);
            return "h3-task-1";
        }

        @Override
        public H3TaskStatus poll(String taskId) {
            polled.add(taskId);
            if (pollError != null) {
                throw pollError;
            }
            return pollResult.apply(taskId);
        }

        @Override
        public void download(H3TaskStatus.Succeeded done, Path target) {
            try {
                Files.createDirectories(target.toAbsolutePath().getParent());
                Files.write(target, downloadContent);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /** Stub coordinator: records the terminal signals the runner fans out. */
    private static final class RecordingCoordinator implements H3TerminalHandler, JobFailureListener {

        private final List<Long> succeeded = new ArrayList<>();
        private final List<Long> failed = new ArrayList<>();
        private final List<String> escalated = new ArrayList<>();

        /** How many onH3Succeeded calls still throw (a failing downstream handler). */
        private int throwsOnSucceeded;

        private void reset() {
            succeeded.clear();
            failed.clear();
            escalated.clear();
            throwsOnSucceeded = 0;
        }

        @Override
        public void onH3Succeeded(GenerationJob job) {
            if (throwsOnSucceeded > 0) {
                throwsOnSucceeded--;
                throw new IllegalStateException("compose planning failed");
            }
            succeeded.add(job.id());
        }

        @Override
        public void onH3Escalate(GenerationJob job, String reason) {
            escalated.add(reason);
        }

        @Override
        public void onFailed(GenerationJob job) {
            failed.add(job.id());
        }
    }
}
