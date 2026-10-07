package com.kiano.commerce;

/**
 * Failure talking to the external commerce platform. Carries a stable
 * machine-readable code (e.g. WOO_UNAVAILABLE, WOO_AUTH_FAILED,
 * WOO_BLOCKED) and whether a later retry could help.
 */
public class CommerceException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public CommerceException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
