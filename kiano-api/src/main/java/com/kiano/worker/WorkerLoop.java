package com.kiano.worker;

import com.kiano.imaging.ImageCodec;
import com.kiano.workerprotocol.CompleteRequest;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.FailRequest;
import com.kiano.workerprotocol.JobPayload;
import com.kiano.workerprotocol.LeaseRequest;
import com.kiano.workerprotocol.LeaseResponse;
import com.kiano.workerprotocol.OutputFile;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The worker's main loop: report executor availability, lease one job, keep
 * the lease alive with heartbeats while an executor runs, download inputs,
 * upload outputs, then report completion or failure. The per-job work
 * directory is always removed. A lost lease discards the result — someone
 * else now owns the job.
 */
@Component
@Profile("worker")
public class WorkerLoop {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerLoop.class);

    private final WorkerClient client;
    private final WorkerProperties properties;
    private final List<GenerationExecutor> executors;

    public WorkerLoop(WorkerClient client, WorkerProperties properties, List<GenerationExecutor> executors) {
        this.client = client;
        this.properties = properties;
        this.executors = List.copyOf(executors);
    }

    @Autowired
    public WorkerLoop(WorkerClient client, WorkerProperties properties,
            ObjectProvider<GenerationExecutor> executors) {
        this(client, properties, executors.orderedStream().toList());
    }

    /** Scheduled entry point: keeps draining jobs while there is work. */
    @Scheduled(fixedDelayString = "${kiano.worker.idle-delay:3s}")
    public void poll() {
        while (runOnce()) {
            // Process the next job immediately.
        }
    }

    /** Handles at most one job; true when a job was handled. */
    public boolean runOnce() {
        Set<ExecutorType> capabilities = new LinkedHashSet<>();
        Set<ExecutorType> unavailable = new LinkedHashSet<>();
        for (GenerationExecutor executor : executors) {
            if (executor.available()) {
                capabilities.add(executor.type());
            } else {
                unavailable.add(executor.type());
            }
        }
        Optional<LeaseResponse> leased;
        try {
            leased = client.lease(new LeaseRequest(properties.getId(), capabilities, unavailable, 1));
        } catch (RuntimeException ex) {
            LOG.warn("Lease request failed (api unreachable?): {}", ex.getMessage());
            return false;
        }
        if (leased.isEmpty()) {
            return false;
        }
        LeaseResponse lease = leased.get();
        Path workDir = properties.getWorkDir().resolve("job-" + lease.job().id());
        try {
            Files.createDirectories(workDir);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot create work dir " + workDir, ex);
        }
        try {
            runJob(lease, workDir);
            return true;
        } finally {
            deleteRecursively(workDir);
        }
    }

    private void runJob(LeaseResponse lease, Path workDir) {
        JobPayload job = lease.job();
        GenerationExecutor executor = executors.stream()
                .filter(e -> e.type() == job.executor() && e.available())
                .findFirst()
                .orElse(null);
        if (executor == null) {
            client.fail(job.id(), new FailRequest("No available executor for " + job.executor(), true, true));
            return;
        }
        AtomicBoolean leaseLost = new AtomicBoolean(false);
        Thread executingThread = Thread.currentThread();
        ScheduledExecutorService heartbeatPool = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "kiano-worker-heartbeat-" + job.id());
            thread.setDaemon(true);
            return thread;
        });
        try {
            long intervalMs = Math.max(1, properties.getHeartbeatInterval().toMillis());
            heartbeatPool.scheduleAtFixedRate(() -> {
                try {
                    if (!client.heartbeat(job.id())) {
                        leaseLost.set(true);
                        heartbeatPool.shutdownNow();
                        // Stop the executor; ComfyUI is interrupted via /interrupt in Task 8.
                        executingThread.interrupt();
                    }
                } catch (RuntimeException ex) {
                    LOG.warn("Heartbeat for job {} failed: {}", job.id(), ex.getMessage());
                }
            }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

            Map<String, Path> inputs = downloadInputs(lease, workDir);
            Map<String, Path> outputs = outputTargets(lease, workDir);
            JobResult result = executor.execute(job, new ExecutionContext(workDir, inputs, outputs));
            if (discardIfLeaseLost(leaseLost, job.id())) {
                return;
            }
            complete(lease, result, outputs);
        } catch (ExecutorUnavailableException ex) {
            if (discardIfLeaseLost(leaseLost, job.id())) {
                return;
            }
            client.fail(job.id(), new FailRequest(describe(ex), true, true));
        } catch (ExecutorException ex) {
            if (discardIfLeaseLost(leaseLost, job.id())) {
                return;
            }
            client.fail(job.id(), new FailRequest(describe(ex), ex.retryable(), false));
        } catch (Exception ex) {
            if (discardIfLeaseLost(leaseLost, job.id())) {
                return;
            }
            LOG.warn("Job {} failed unexpectedly", job.id(), ex);
            client.fail(job.id(), new FailRequest(describe(ex), true, false));
        } finally {
            heartbeatPool.shutdownNow();
        }
    }

    private Map<String, Path> downloadInputs(LeaseResponse lease, Path workDir) throws IOException {
        Path inDir = workDir.resolve("in");
        Files.createDirectories(inDir);
        Map<String, Path> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, URI> entry : lease.inputUrls().entrySet()) {
            Path target = inDir.resolve(entry.getKey());
            client.download(entry.getValue(), target);
            inputs.put(entry.getKey(), target);
        }
        return inputs;
    }

    private Map<String, Path> outputTargets(LeaseResponse lease, Path workDir) throws IOException {
        Path outDir = workDir.resolve("out");
        Files.createDirectories(outDir);
        Map<String, Path> outputs = new LinkedHashMap<>();
        for (String name : lease.outputUploadUrls().keySet()) {
            outputs.put(name, outDir.resolve(name));
        }
        return outputs;
    }

    private void complete(LeaseResponse lease, JobResult result, Map<String, Path> outputs) throws IOException {
        JobPayload job = lease.job();
        List<OutputFile> files = new ArrayList<>();
        for (Map.Entry<String, Path> entry : outputs.entrySet()) {
            String name = entry.getKey();
            Path file = entry.getValue();
            byte[] bytes = Files.readAllBytes(file);
            BufferedImage image = readImage(bytes);
            client.upload(lease.outputUploadUrls().get(name), file, "image/png");
            files.add(new OutputFile(name,
                    job.input().path("outputs").path(name).asString(),
                    image == null ? null : image.getWidth(),
                    image == null ? null : image.getHeight(),
                    null,
                    sha256Hex(bytes)));
        }
        if (!client.complete(job.id(), new CompleteRequest(files, result.gpuSeconds()))) {
            LOG.warn("Lease on job {} was lost while completing; the job is no longer ours", job.id());
        }
    }

    /** Clears the interrupt raised by the heartbeat watchdog before discarding. */
    private static boolean discardIfLeaseLost(AtomicBoolean leaseLost, long jobId) {
        if (!leaseLost.get()) {
            return false;
        }
        Thread.interrupted();
        LOG.warn("Lease on job {} was lost mid-flight; discarding the result", jobId);
        return true;
    }

    private static BufferedImage readImage(byte[] bytes) {
        try {
            return ImageCodec.read(bytes);
        } catch (RuntimeException ex) {
            return null; // not a decodable image (e.g. a video in later steps)
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        if (ex instanceof ExecutorException || message == null) {
            return message == null ? ex.toString() : message;
        }
        return ex.getClass().getSimpleName() + ": " + message;
    }

    private static void deleteRecursively(@Nullable Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var children = Files.walk(dir)) {
            children.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ex) {
                    LOG.warn("Could not delete {}: {}", path, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            LOG.warn("Could not clean up work dir {}: {}", dir, ex.getMessage());
        }
    }
}
