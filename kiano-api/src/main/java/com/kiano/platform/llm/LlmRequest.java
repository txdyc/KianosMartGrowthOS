package com.kiano.platform.llm;

import java.util.List;

/**
 * One gateway call: system prompt, user text, optional images, the record
 * class the structured output is parsed into, the desired thinking effort and
 * the token budget. Images are always sent before the text block.
 */
public record LlmRequest<T>(long tenantId, LlmPurpose purpose, String system, String userText,
        List<LlmImage> images, Class<T> outputType, Effort effort, long maxTokens) {

    /**
     * Thinking depth across a call. FACT_DRAFT uses HIGH (accuracy first),
     * copy uses MEDIUM. Sent as OutputConfig.effort - never as a thinking
     * budget, because Opus 5.5 rejects ThinkingConfigDisabled/budgetTokens.
     */
    public enum Effort {
        LOW,
        MEDIUM,
        HIGH
    }
}