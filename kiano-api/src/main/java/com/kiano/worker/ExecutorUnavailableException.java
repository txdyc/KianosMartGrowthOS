package com.kiano.worker;

/**
 * The executor's backend is down (ComfyUI unreachable, model missing): the
 * api parks the job in WAITING_EXECUTOR without consuming an attempt.
 */
public class ExecutorUnavailableException extends ExecutorException {

    public ExecutorUnavailableException(String message) {
        super(message, true);
    }

    @Override
    public boolean retryable() {
        return true;
    }
}
