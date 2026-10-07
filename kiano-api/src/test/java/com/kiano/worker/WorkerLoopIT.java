package com.kiano.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.KianoApplication;
import com.kiano.TestcontainersConfiguration;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.content.generation.JobStatus;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobPayload;
import com.kiano.workerprotocol.JobStep;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full loop against the real api on a random port (Testcontainers PG +
 * MinIO): the worker leases a COMPOSITE job via the TEST_ECHO executor,
 * downloads, executes, uploads and completes — plus the failure paths.
 */
// classes=: the test sits next to KianoWorkerApplication, which the boot
// configuration search would otherwise pick instead of the api.
@SpringBootTest(classes = KianoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WorkerLoopIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private GenerationRunStore runs;

    @Autowired
    private ObjectStorage storage;

    @Value("${local.server.port}")
    private int port;

    private long tenantId;
    private long productId;
    private long runId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from worker_status");
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
                        + "values (?, ?, ?, 'simple', 'WORKER-LOOP-1', 'Worker Loop Test', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000));
        runId = runs.create(tenantId, productId, null);
    }

    @Test
    void runOnce_leasesExecutesUploadsAndCompletes() throws Exception {
        byte[] png = ImageCodec.png(new BufferedImage(31, 17, BufferedImage.TYPE_INT_RGB));
        String inputKey = "worker-it/" + System.nanoTime() + "/in.png";
        String outputKey = "worker-it/" + System.nanoTime() + "/out.png";
        storage.put(inputKey, png, "image/png");
        long jobId = jobs.create(tenantId, productId, runId, JobStep.WHITE_MAIN, "A",
                Map.of("inputs", Map.of("image", inputKey), "outputs", Map.of("image", outputKey)),
                List.of());

        WorkerLoop loop = newLoop(new EchoExecutor());
        assertThat(loop.runOnce()).isTrue();

        GenerationJob job = jobs.find(tenantId, jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.gpuSeconds()).isEqualTo(0.5);
        assertThat(storage.exists(outputKey)).isTrue();
        assertThat(job.output().path("outputs").path("image").path("width").asInt()).isEqualTo(31);
        assertThat(job.output().path("outputs").path("image").path("height").asInt()).isEqualTo(17);
        assertThat(job.output().path("outputs").path("image").path("sha256").asString()).hasSize(64);
        // The run is recomputed after completion.
        assertThat(runs.latest(tenantId, productId).orElseThrow().status()).isEqualTo("DONE");
    }

    @Test
    void runOnce_executorUnavailable_reportsWaiting() throws Exception {
        long jobId = jobs.create(tenantId, productId, runId, JobStep.WHITE_MAIN, "A",
                Map.of("inputs", Map.of(), "outputs", Map.of("image", "worker-it/x.png")), List.of());

        WorkerLoop loop = newLoop(new LambdaExecutor(job -> {
            throw new ExecutorUnavailableException("comfy is down");
        }));
        assertThat(loop.runOnce()).isTrue();

        GenerationJob job = jobs.find(tenantId, jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.WAITING_EXECUTOR);
        assertThat(job.attempts()).isZero();
        assertThat(job.error()).contains("comfy is down");
    }

    @Test
    void runOnce_nonRetryableException_failsJob() throws Exception {
        long jobId = jobs.create(tenantId, productId, runId, JobStep.WHITE_MAIN, "A",
                Map.of("inputs", Map.of(), "outputs", Map.of("image", "worker-it/y.png")), List.of());

        WorkerLoop loop = newLoop(new LambdaExecutor(job -> {
            throw new ExecutorException("CUTOUT_EMPTY: nothing to compose", false);
        }));
        assertThat(loop.runOnce()).isTrue();

        GenerationJob job = jobs.find(tenantId, jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.error()).contains("CUTOUT_EMPTY");
    }

    @Test
    void runOnce_noJob_returnsFalse() throws Exception {
        WorkerLoop loop = newLoop(new EchoExecutor());
        assertThat(loop.runOnce()).isFalse();
    }

    private WorkerLoop newLoop(GenerationExecutor executor) throws Exception {
        WorkerProperties properties = new WorkerProperties();
        properties.setApiBaseUrl("http://localhost:" + port);
        properties.setToken("test-worker-token");
        properties.setId("it-worker");
        properties.setWorkDir(Files.createTempDirectory("kiano-worker-it"));
        properties.setHeartbeatInterval(Duration.ofSeconds(30));
        return new WorkerLoop(new HttpWorkerClient(properties), properties, List.of(executor));
    }

    /** Delegates to a lambda body; used for the failure-path tests. */
    static class LambdaExecutor implements GenerationExecutor {

        private final ThrowingExecute body;

        LambdaExecutor(ThrowingExecute body) {
            this.body = body;
        }

        @Override
        public ExecutorType type() {
            return ExecutorType.COMPOSITE;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public JobResult execute(JobPayload job, ExecutionContext ctx) throws ExecutorException {
            return body.execute(job);
        }
    }

    @FunctionalInterface
    interface ThrowingExecute {

        JobResult execute(JobPayload job) throws ExecutorException;
    }

    /** Copies the "image" input to the "image" output. */
    static class EchoExecutor implements GenerationExecutor {

        @Override
        public ExecutorType type() {
            return ExecutorType.COMPOSITE;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public JobResult execute(JobPayload job, ExecutionContext ctx) throws ExecutorException {
            try {
                Files.copy(ctx.inputs().get("image"), ctx.outputs().get("image"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return new JobResult(0.5);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
