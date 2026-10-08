package com.kiano.platform.llm;

/**
 * Why a call was made; recorded in llm_call.purpose for cost reporting.
 * AD_COPY reuses the COPY route for resolution but is ledged separately.
 */
public enum LlmPurpose {
    FACT_DRAFT,
    COPY,
    AD_COPY,
    CONNECTION_TEST
}