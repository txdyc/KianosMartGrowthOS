package com.kiano.content.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kiano.TestcontainersConfiguration;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Worker protocol endpoints: lease with presigned URLs, heartbeat,
 * complete with the output contract check, and fail. Uses the worker
 * token and seeds jobs directly through the store.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WorkerControllerTest {

    private static final String WORKER_TOKEN = "test-worker-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private GenerationRunStore runs;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private RecordingCompletionListener listener;

    @Value("${kiano.storage.public-endpoint}")
    private String publicEndpoint;

    private long tenantId;
    private long productId;
    private long runId;
    private String prefix;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from worker_status");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'", Long.class);
        productId = insertProduct("WORKER-CTRL-" + System.nanoTime());
        runId = runs.create(tenantId, productId, null);
        prefix = "worker-ctrl/" + System.nanoTime();
        listener.succeeded.clear();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ListenerConfig {
        @Bean
        RecordingCompletionListener recordingCompletionListener() {
            return new RecordingCompletionListener();
        }
    }

    static class RecordingCompletionListener implements JobCompletionListener {
        final List<GenerationJob> succeeded = new ArrayList<>();

        @Override
        public void onSucceeded(GenerationJob job) {
            succeeded.add(job);
        }
    }

    @Test
    void lease_returnsJobWithPresignedUrls() throws Exception {
        long id = createJob(JobStep.CUTOUT, "P1");
        lease("w-1", "[\"COMFYUI\"]", "[]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.id").value(id))
                .andExpect(jsonPath("$.job.step").value("CUTOUT"))
                .andExpect(jsonPath("$.job.variant").value("P1"))
                .andExpect(jsonPath("$.job.executor").value("COMFYUI"))
                .andExpect(jsonPath("$.job.input.inputs.image").value(prefix + "/in.png"))
                .andExpect(jsonPath("$.inputUrls.image", startsWith(publicEndpoint)))
                .andExpect(jsonPath("$.outputUploadUrls.cutout", startsWith(publicEndpoint)));
    }

    @Test
    void lease_noJob_204() throws Exception {
        createJob(JobStep.CUTOUT, "P1");
        lease("w-1", "[\"COMPOSITE\"]", "[]")
                .andExpect(status().isNoContent());
    }

    @Test
    void lease_reportsUnavailable_marksComfyJobsWaiting() throws Exception {
        long comfyId = createJob(JobStep.CUTOUT, "P1");
        long compositeId = createJob(JobStep.WHITE_MAIN, "main");
        lease("w-1", "[\"COMPOSITE\"]", "[\"COMFYUI\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.id").value(compositeId));
        assertThat(jobStatus(comfyId)).isEqualTo(JobStatus.WAITING_EXECUTOR);
        assertThat(jobStatus(compositeId)).isEqualTo(JobStatus.LEASED);
    }

    @Test
    void lease_capabilityBack_releasesWaiting() throws Exception {
        long comfyId = createJob(JobStep.CUTOUT, "P1");
        lease("w-1", "[]", "[\"COMFYUI\"]")
                .andExpect(status().isNoContent());
        assertThat(jobStatus(comfyId)).isEqualTo(JobStatus.WAITING_EXECUTOR);
        lease("w-1", "[\"COMFYUI\"]", "[]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job.id").value(comfyId));
    }

    @Test
    void heartbeat_ok_thenLeaseLost409() throws Exception {
        long id = createJob(JobStep.CUTOUT, "P1");
        jobs.lease("w-1", Set.of(ExecutorType.COMFYUI));
        mockMvc.perform(workerAuth(post("/api/v1/worker/jobs/{id}/heartbeat", id))
                        .header("X-Worker-Id", "w-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mockMvc.perform(workerAuth(post("/api/v1/worker/jobs/{id}/heartbeat", id))
                        .header("X-Worker-Id", "w-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_LOST"));
    }

    @Test
    void complete_missingOutputObject_422_andRequeued() throws Exception {
        long id = createJob(JobStep.CUTOUT, "P1");
        jobs.lease("w-1", Set.of(ExecutorType.COMFYUI));
        // The contract key was never uploaded to storage.
        mockMvc.perform(workerAuth(post("/api/v1/worker/jobs/{id}/complete", id))
                        .header("X-Worker-Id", "w-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTPUT_MISSING"));
        GenerationJob job = jobs.find(tenantId, id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.error()).contains("OUTPUT_MISSING");
        assertThat(listener.succeeded).isEmpty();
    }

    @Test
    void complete_success_callsListenerOnce() throws Exception {
        long id = createJob(JobStep.CUTOUT, "P1");
        jobs.lease("w-1", Set.of(ExecutorType.COMFYUI));
        storage.put(prefix + "/out.png", new byte[]{1, 2, 3, 4}, "image/png");
        mockMvc.perform(workerAuth(post("/api/v1/worker/jobs/{id}/complete", id))
                        .header("X-Worker-Id", "w-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody()))
                .andExpect(status().isOk());
        assertThat(listener.succeeded).hasSize(1);
        assertThat(listener.succeeded.get(0).id()).isEqualTo(id);
        GenerationJob job = jobs.find(tenantId, id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.output().path("outputs").path("cutout").path("objectKey").asString())
                .isEqualTo(prefix + "/out.png");
        assertThat(job.gpuSeconds()).isEqualTo(1.5);
        assertThat(runs.latest(tenantId, productId).orElseThrow().status()).isEqualTo("DONE");
    }

    @Test
    void complete_afterLeaseReaped_returns409() throws Exception {
        long id = createJob(JobStep.CUTOUT, "P1");
        jobs.lease("w-1", Set.of(ExecutorType.COMFYUI));
        storage.put(prefix + "/out.png", new byte[]{1, 2, 3, 4}, "image/png");
        jdbcTemplate.update(
                "update generation_job set lease_expires_at = now() - interval '5 seconds' where id = ?", id);
        jobs.reapExpiredLeases();
        mockMvc.perform(workerAuth(post("/api/v1/worker/jobs/{id}/complete", id))
                        .header("X-Worker-Id", "w-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEASE_LOST"));
        assertThat(listener.succeeded).isEmpty();
    }

    @Test
    void fail_executorUnavailable_waiting() throws Exception {
        long id = createJob(JobStep.CUTOUT, "P1");
        jobs.lease("w-1", Set.of(ExecutorType.COMFYUI));
        mockMvc.perform(workerAuth(post("/api/v1/worker/jobs/{id}/fail", id))
                        .header("X-Worker-Id", "w-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"error\":\"comfy offline\",\"retryable\":true,\"executorUnavailable\":true}"))
                .andExpect(status().isOk());
        GenerationJob job = jobs.find(tenantId, id).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.WAITING_EXECUTOR);
        assertThat(job.attempts()).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions lease(String workerId, String capabilities,
            String unavailable) throws Exception {
        return mockMvc.perform(workerAuth(post("/api/v1/worker/lease"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workerId\":\"%s\",\"capabilities\":%s,\"unavailable\":%s,\"max\":1}"
                        .formatted(workerId, capabilities, unavailable)));
    }

    private String completeBody() {
        return """
                {"outputs":[{"name":"cutout","objectKey":"%s/out.png","width":800,"height":800,"sha256":"aa"}],\
                "gpuSeconds":1.5}
                """.formatted(prefix);
    }

    private MockHttpServletRequestBuilder workerAuth(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_TOKEN);
    }

    private JobStatus jobStatus(long jobId) {
        return jobs.find(tenantId, jobId).orElseThrow().status();
    }

    private long createJob(JobStep step, String variant) {
        return jobs.create(tenantId, productId, runId, step, variant,
                Map.of("inputs", Map.of("image", prefix + "/in.png"),
                        "outputs", Map.of("cutout", prefix + "/out.png")),
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
                        + "values (?, ?, ?, 'simple', ?, 'Worker ctrl test', 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), sku);
    }
}
