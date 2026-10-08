package com.kiano.platform.llm;

/**
 * A failing LLM call. {@link #retryable()} decides whether the queue may back
 * off and retry; stable codes: LLM_REFUSED, LLM_TRUNCATED, LLM_CONFIG,
 * LLM_BAD_REQUEST, LLM_UNAVAILABLE, LLM_INVALID_OUTPUT, LLM_NOT_CONFIGURED.
 */
public class LlmException extends Exception {

    private final String code;
    private final boolean retryable;

    public LlmException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}