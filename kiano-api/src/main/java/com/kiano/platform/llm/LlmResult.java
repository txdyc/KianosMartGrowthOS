package com.kiano.platform.llm;

import java.math.BigDecimal;

/**
 * Outcome of a completed call: the parsed structured output plus the ledger
 * entry (model, usage and cost) that was written to llm_call.
 */
public record LlmResult<T>(T output, String model, long inputTokens, long outputTokens,
        BigDecimal costUsd, long llmCallId) {
}