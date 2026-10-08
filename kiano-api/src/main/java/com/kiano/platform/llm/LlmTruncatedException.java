package com.kiano.platform.llm;

/**
 * The output hit maxTokens (stop_reason = max_tokens). Not retryable: the
 * task queue would re-send the identical request, truncate again and bill the
 * full output every time. Copy and fact outputs are a few thousand tokens, so
 * hitting the 16k budget signals a runaway generation worth surfacing.
 */
public class LlmTruncatedException extends LlmException {

    public LlmTruncatedException(String message) {
        super("LLM_TRUNCATED", message, false);
    }
}