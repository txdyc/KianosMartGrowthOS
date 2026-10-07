package com.kiano.worker;

/**
 * Executor failure. {@code retryable} decides whether the api requeues the
 * job for another attempt.
 */
public class ExecutorException extends Exception {

    private final boolean retryable;

    public ExecutorException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
