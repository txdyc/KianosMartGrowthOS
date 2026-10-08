package com.kiano.platform.llm;

/**
 * Why a call was made; recorded in llm_call.purpose for cost reporting.
 */
public enum LlmPurpose {
    FACT_DRAFT,
    COPY,
    CONNECTION_TEST
}