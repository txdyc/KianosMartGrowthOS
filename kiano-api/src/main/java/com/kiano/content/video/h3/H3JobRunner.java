package com.kiano.content.video.h3;

import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.batch.BatchProperties;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.JobFailureListener;
import com.kiano.content.generation.JobStatus;
import com.kiano.content.video.VideoType;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.workerprotocol.ContentTypes;
import com.kiano.workerprotocol.ExecutorType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Executes {@code H3_CLIP_API} jobs inside the api (C5 plan §4.1) with the
 * submit → park → poll pattern: the first round checks the daily budget, reads
 * the reviewed first frame from storage, submits the I2VA prompt and parks the
 * job with the provider task id in {@code external_ref}; later rounds poll,
 * and either upload the finished clip to {@code outputs.clip} and complete with
 * the cost ledger entry, or fail the job. Waits past {@code api.max-wait} fail
 * with {@code H3_TIMEOUT}. Retryable failures back off in the store and only a
 * final FAILED notifies {@link JobFailureListener}. Workers never see these
 * jobs (the lease endpoint filters H3 out), so this runner is api-only.
 */
@Component
@Profile("!worker")
public class H3JobRunner {

    /** Lease owner of H3 API jobs; distinct from any worker id. */
    public static final String LEASE_OWNER = "api-h3";

    private static final int MAX_JOBS_PER_ROUND = 2;
    private static final String SOURCE = "VIDEO";

    private final GenerationJobStore jobs;
    private final ObjectStorage storage;
    private final H3Client client;
    private final H3Properties properties;
    private final H3PromptBuilder promptBuilder;
    private final ProductCatalog products;
    private final AuditLog auditLog;
    private final BatchProperties batchProperties;
    private final ObjectProvider<H3TerminalHandler> terminalHandler;
    private final ObjectProvider<JobFailureListener> failureListener;

    public H3JobRunner(GenerationJobStore jobs, ObjectStorage storage, H3Client client,
            H3Properties properties, H3PromptBuilder promptBuilder, ProductCatalog products,
            AuditLog auditLog, BatchProperties batchProperties,
            ObjectProvider<H3TerminalHandler> terminalHandler,
            ObjectProvider<JobFailureListener> failureListener) {
        this.jobs = jobs;
        this.storage = storage;
        this.client = client;
        this.properties = properties;
        this.promptBuilder = promptBuilder;
        this.products = products;
        this.auditLog = auditLog;
        this.batchProperties = batchProperties;
        this.terminalHandler = terminalHandler;
        this.failureListener = failureListener;
    }

    @Scheduled(fixedDelayString = "${kiano.content.video.h3.api.poll-interval:20s}",
            initialDelayString = "${kiano.content.video.h3.api.poll-interval:20s}")
    public void scheduled() {
        if (properties.getApi().isEnabled()) {
            pollOnce();
        }
    }

    /** One round: leases at most two H3 jobs and advances each of them. */
    public void pollOnce() {
        for (int handled = 0; handled < MAX_JOBS_PER_ROUND; handled++) {
            Optional<GenerationJob> leased = jobs.lease(LEASE_OWNER, Set.of(ExecutorType.H3),
                    batchProperties.nightOpen(java.time.Instant.now()));
            if (leased.isEmpty()) {
                return;
            }
            advance(leased.get());
        }
    }

    private void advance(GenerationJob job) {
        try {
            if (job.externalRef() == null || job.externalRef().isBlank()) {
                submit(job);
            } else {
                poll(job);
            }
        } catch (H3ClientException ex) {
            fail(job, ex.contentRejected() ? "H3_CONTENT_REJECTED" : "H3_FAILED",
                    ex.getMessage(), ex.retryable());
        }
    }

    private void submit(GenerationJob job) {
        BigDecimal cost = estimatedCost();
        BigDecimal spent = jobs.costSince(job.tenantId(), ExecutorType.H3, startOfUtcDay());
        if (spent.add(cost).compareTo(properties.getApi().getDailyBudgetUsd()) > 0) {
            fail(job, "H3_BUDGET_EXCEEDED", "daily H3 budget of "
                    + properties.getApi().getDailyBudgetUsd() + " USD would be exceeded (spent today: "
                    + spent + " USD, this clip needs " + cost + " USD)", false);
            return;
        }
        String firstFrameKey = job.input().path("firstFrameKey").asString();
        String prompt = prompt(job);
        String taskId = client.submit(new H3Request(prompt, storage.download(firstFrameKey),
                properties.getRequestSeconds(), properties.getApi().getResolution()));
        jobs.park(job.id(), LEASE_OWNER, taskId, properties.getApi().getPollInterval());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("jobId", job.id());
        after.put("taskId", taskId);
        after.put("seconds", properties.getRequestSeconds());
        after.put("costUsd", cost);
        auditLog.record(new AuditEntry(job.tenantId(), ActorType.SYSTEM, "SYSTEM",
                "H3_CLIP_SUBMITTED", "generation_job", String.valueOf(job.id()), null, after,
                null, SOURCE));
    }

    private void poll(GenerationJob job) {
        H3TaskStatus status = client.poll(job.externalRef());
        if (status instanceof H3TaskStatus.Pending) {
            if (Duration.between(job.createdAt(), Instant.now())
                    .compareTo(properties.getApi().getMaxWait()) > 0) {
                fail(job, "H3_TIMEOUT", "H3 task " + job.externalRef() + " did not finish within "
                        + properties.getApi().getMaxWait(), false);
            } else {
                jobs.park(job.id(), LEASE_OWNER, job.externalRef(), properties.getApi().getPollInterval());
            }
            return;
        }
        if (status instanceof H3TaskStatus.Succeeded done) {
            complete(job, done);
            return;
        }
        H3TaskStatus.Failed failed = (H3TaskStatus.Failed) status;
        fail(job, failed.contentRejected() ? "H3_CONTENT_REJECTED" : "H3_FAILED",
                failed.reason(), failed.retryable());
    }

    private void complete(GenerationJob job, H3TaskStatus.Succeeded done) {
        String key = job.input().path("outputs").path("clip").asString();
        if (key.isBlank()) {
            fail(job, "H3_FAILED", "job input has no outputs.clip object key", false);
            return;
        }
        Path temp = tempFile();
        try {
            client.download(done, temp);
            storage.put(key, temp, ContentTypes.forKey(key));
        } finally {
            deleteQuietly(temp);
        }
        BigDecimal cost = estimatedCost();
        Map<String, Object> clip = new LinkedHashMap<>();
        clip.put("objectKey", key);
        clip.put("durationS", properties.getRequestSeconds());
        Optional<GenerationJob> completed = jobs.complete(job.id(), LEASE_OWNER,
                Map.of("outputs", Map.of("clip", clip)), 0, cost);
        if (completed.isEmpty()) {
            // the lease was lost (cancelled or reaped): drop the result
            return;
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("jobId", job.id());
        after.put("taskId", job.externalRef());
        after.put("seconds", properties.getRequestSeconds());
        after.put("costUsd", cost);
        auditLog.record(new AuditEntry(job.tenantId(), ActorType.SYSTEM, "SYSTEM",
                "H3_CLIP_FINISHED", "generation_job", String.valueOf(job.id()), null, after,
                null, SOURCE));
        GenerationJob succeeded = completed.get();
        terminalHandler.orderedStream().forEach(handler -> handler.onH3Succeeded(succeeded));
    }

    private void fail(GenerationJob job, String code, @Nullable String detail, boolean retryable) {
        String error = code + (detail == null || detail.isBlank() ? "" : ": " + detail);
        jobs.fail(job.id(), LEASE_OWNER, error, retryable, false)
                .filter(failed -> failed.status() == JobStatus.FAILED)
                .ifPresent(failed -> failureListener.orderedStream()
                        .forEach(listener -> listener.onFailed(failed)));
    }

    private String prompt(GenerationJob job) {
        String prebuilt = job.input().path("prompt").asString();
        if (!prebuilt.isBlank()) {
            return prebuilt;
        }
        VideoType type = VideoType.fromWire(job.input().path("type").asString("demo"));
        String sceneLabel = job.input().path("sceneLabel").asString();
        ProductView product = products.findById(job.tenantId(), job.productId())
                .orElseThrow(() -> new H3ClientException(
                        "H3 job " + job.id() + " references missing product " + job.productId(),
                        false, false));
        return promptBuilder.build(job.tenantId(), type, product, sceneLabel);
    }

    private BigDecimal estimatedCost() {
        return properties.getApi().getPricePerSecondUsd()
                .multiply(BigDecimal.valueOf(properties.getRequestSeconds()));
    }

    private static Instant startOfUtcDay() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Path tempFile() {
        try {
            return Files.createTempFile("kiano-h3-", ".mp4");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // the temp file is best-effort cleanup
        }
    }
}