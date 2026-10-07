package com.kiano.platform.queue;

/**
 * Thrown by a {@link TaskHandler} to fail the task immediately, without retries.
 */
public class NonRetryableTaskException extends RuntimeException {

    public NonRetryableTaskException(String message) {
        super(message);
    }

    public NonRetryableTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}
