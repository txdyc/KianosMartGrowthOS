package com.kiano.worker;

import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobPayload;

/**
 * One executor kind runnable by the worker process (spec §4.3): a
 * deterministic compositor or a ComfyUI client. Implementations must be
 * stateless apart from a short-lived availability cache.
 */
public interface GenerationExecutor {

    ExecutorType type();

    /** Health probe; implementations cache the result for about 10 seconds. */
    boolean available();

    /**
     * Runs the job, writing every promised output to the path given in
     * ctx.outputs; throwing reports failure.
     */
    JobResult execute(JobPayload job, ExecutionContext ctx) throws ExecutorException;
}
