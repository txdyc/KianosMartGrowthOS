package com.kiano.platform.llm;

/**
 * The output hit maxTokens (stop_reason = max_tokens). Retryable: the caller
 * may retry once with the token budget doubled.
 */
public class LlmTruncatedException extends LlmException {

    public LlmTruncatedException(String message) {
        super("LLM_TRUNCATED", message, true);
    }
}