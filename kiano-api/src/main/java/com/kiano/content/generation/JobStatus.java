package com.kiano.content.generation;

/**
 * Lifecycle of a generation job. WAITING_EXECUTOR never consumes an attempt.
 */
public enum JobStatus {
    QUEUED,
    LEASED,
    WAITING_EXECUTOR,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
