package com.kiano.content.asset;

/** Automatic precheck findings that need reviewer attention. */
public enum PrecheckFlag {
    // Image flags (C2)
    PRODUCT_MISMATCH,
    AI_TEXT,
    EDGE_NOT_WHITE,
    OCCUPANCY_OUT_OF_RANGE,
    // Text flags (C3)
    FACT_MISMATCH,
    FORBIDDEN_CLAIM,
    TOO_LONG,
    PRICE_IN_COPY,
    MISSING_PLACEHOLDER,
    POLICY_PENDING
}
