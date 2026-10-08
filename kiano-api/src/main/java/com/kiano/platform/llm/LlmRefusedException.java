package com.kiano.platform.llm;

import org.jspecify.annotations.Nullable;

/**
 * The model refused the request (stop_reason = refusal). Never retried; the
 * UI tells the operator to fill the data manually. Carries the refusal
 * {@code category} recorded in stop_details.
 */
public class LlmRefusedException extends LlmException {

    private final @Nullable String category;

    public LlmRefusedException(String message, @Nullable String category) {
        super("LLM_REFUSED", message, false);
        this.category = category;
    }

    /** Refusal category from stop_details (e.g. "unsafe"), when the API sent one. */
    public @Nullable String category() {
        return category;
    }
}