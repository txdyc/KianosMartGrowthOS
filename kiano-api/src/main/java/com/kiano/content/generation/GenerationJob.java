package com.kiano.content.generation;

import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobStep;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One row of generation_job as returned to workers and the pipeline.
 */
public record GenerationJob(
        long id,
        long tenantId,
        long productId,
        long runId,
        JobStep step,
        String variant,
        ExecutorType executor,
        JsonNode input,
        List<Long> parentJobIds,
        JobStatus status,
        int attempts,
        int maxAttempts,
        String leaseOwner,
        Instant leaseExpiresAt,
        JsonNode output,
        Double gpuSeconds,
        String error,
        Instant createdAt,
        Instant finishedAt) {
}
