package com.kiano.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.workerprotocol.CompleteRequest;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.FailRequest;
import com.kiano.workerprotocol.JobPayload;
import com.kiano.workerprotocol.JobStep;
import com.kiano.workerprotocol.LeaseRequest;
import com.kiano.workerprotocol.LeaseResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WorkerLoop with a fake WorkerClient: lease requests carry availability,
 * a lost lease discards the result without completing, and the work
 * directory is always cleaned up.
 */
class WorkerLoopTest {

    @TempDir
    Path tempDir;

    @Test
    void leaseLost_discardsResultAndContinues() throws Exception {
        FakeClient client = new FakeClient();
        client.nextLease = leaseOf(1L);
        client.heartbeatOk = false;
        WorkerProperties properties = properties(Duration.ofMillis(20));
        WorkerLoop loop = new WorkerLoop(client, properties,
                List.of(new EchoExecutor(Duration.ofMillis(300))));

        assertThat(loop.runOnce()).isTrue();

        assertThat(client.heartbeats).isNotEmpty();
        assertThat(client.completes).isEmpty();
        assertThat(client.fails).isEmpty();
        // Continues: next poll finds nothing.
        client.nextLease = null;
        assertThat(loop.runOnce()).isFalse();
    }

    @Test
    void unavailableExecutor_reportedInLeaseRequest() {
        FakeClient client = new FakeClient();
        client.nextLease = null;
        WorkerLoop loop = new WorkerLoop(client, properties(Duration.ofHours(1)),
                List.of(new EchoExecutor(Duration.ZERO), new UnavailableExecutor()));

        assertThat(loop.runOnce()).isFalse();

        assertThat(client.leaseRequests).hasSize(1);
        LeaseRequest request = client.leaseRequests.get(0);
        assertThat(request.capabilities()).containsExactly(ExecutorType.COMPOSITE);
        assertThat(request.unavailable()).containsExactly(ExecutorType.COMFYUI);
    }

    @Test
    void workDir_alwaysCleanedUp() throws Exception {
        FakeClient client = new FakeClient();
        client.nextLease = leaseOf(1L);
        WorkerLoop loop = new WorkerLoop(client, properties(Duration.ofHours(1)),
                List.of(new EchoExecutor(Duration.ZERO)));
        loop.runOnce();
        assertThat(listChildren()).isEmpty();

        client.nextLease = leaseOf(2L);
        client.heartbeatOk = true;
        loop = new WorkerLoop(client, properties(Duration.ofHours(1)),
                List.of(new FailingExecutor(new ExecutorException("boom", false))));
        loop.runOnce();
        assertThat(client.fails).hasSize(1);
        assertThat(listChildren()).isEmpty();
    }

    private List<String> listChildren() throws IOException {
        try (var stream = Files.list(tempDir)) {
            return stream.map(p -> p.getFileName().toString()).toList();
        }
    }

    private WorkerProperties properties(Duration heartbeatInterval) {
        WorkerProperties properties = new WorkerProperties();
        properties.setApiBaseUrl("http://localhost:0");
        properties.setToken("test-worker-token");
        properties.setId("unit-worker");
        properties.setWorkDir(tempDir);
        properties.setHeartbeatInterval(heartbeatInterval);
        return properties;
    }

    private static LeaseResponse leaseOf(long jobId) {
        JobPayload payload = new JobPayload(jobId, JobStep.WHITE_MAIN, "A", ExecutorType.COMPOSITE,
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(
                        "{\"inputs\":{\"image\":\"k\"},\"outputs\":{\"image\":\"out\"}}"));
        return new LeaseResponse(payload,
                Map.of("image", URI.create("fake://bucket/worker-it/in/image.png")),
                Map.of("image", URI.create("fake://bucket/worker-it/out/image.png")));
    }

    /** Records every call; downloads serve prepared bytes, uploads are captured. */
    static class FakeClient implements WorkerClient {

        LeaseResponse nextLease;
        boolean heartbeatOk = true;
        byte[] inputBytes = new byte[] {1, 2, 3};
        final List<LeaseRequest> leaseRequests = new ArrayList<>();
        final List<Long> heartbeats = new ArrayList<>();
        final List<CompleteRequest> completes = new ArrayList<>();
        final List<FailRequest> fails = new ArrayList<>();

        @Override
        public Optional<LeaseResponse> lease(LeaseRequest request) {
            leaseRequests.add(request);
            return Optional.ofNullable(nextLease);
        }

        @Override
        public boolean heartbeat(long jobId) {
            heartbeats.add(jobId);
            return heartbeatOk;
        }

        @Override
        public boolean complete(long jobId, CompleteRequest request) {
            completes.add(request);
            return true;
        }

        @Override
        public boolean fail(long jobId, FailRequest request) {
            fails.add(request);
            return true;
        }

        @Override
        public void download(URI url, Path target) throws IOException {
            Files.write(target, inputBytes);
        }

        @Override
        public void upload(URI url, Path file, String contentType) {
            // no-op: bytes stay local
        }
    }

    /** Copies the "image" input to the "image" output, sleeping meanwhile. */
    static class EchoExecutor implements GenerationExecutor {

        private final Duration sleep;

        EchoExecutor(Duration sleep) {
            this.sleep = sleep;
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
            try {
                if (!sleep.isZero()) {
                    Thread.sleep(sleep.toMillis());
                }
                Files.copy(ctx.inputs().get("image"), ctx.outputs().get("image"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return new JobResult(0.5);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", ex);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    static class UnavailableExecutor implements GenerationExecutor {

        @Override
        public ExecutorType type() {
            return ExecutorType.COMFYUI;
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public JobResult execute(JobPayload job, ExecutionContext ctx) throws ExecutorException {
            throw new ExecutorException("should not run", false);
        }
    }

    static class FailingExecutor implements GenerationExecutor {

        private final ExecutorException failure;

        FailingExecutor(ExecutorException failure) {
            this.failure = failure;
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
            throw failure;
        }
    }
}
