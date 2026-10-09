package com.kiano.platform.llm;

/**
 * Why a call was made; recorded in llm_call.purpose for cost reporting.
 * AD_COPY and VIDEO_SCRIPT reuse the COPY route for resolution but are
 * ledged separately.
 */
public enum LlmPurpose {
    FACT_DRAFT,
    COPY,
    AD_COPY,
    VIDEO_SCRIPT,
    CONNECTION_TEST
}