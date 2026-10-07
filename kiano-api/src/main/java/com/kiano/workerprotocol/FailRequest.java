package com.kiano.workerprotocol;

/**
 * Job failure report. executorUnavailable parks the job in WAITING_EXECUTOR
 * without consuming an attempt.
 */
public record FailRequest(String error, boolean retryable, boolean executorUnavailable) {
}
