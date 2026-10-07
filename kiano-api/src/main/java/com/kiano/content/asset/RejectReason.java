package com.kiano.content.asset;

/** Why an asset was rejected (spec §8). */
public enum RejectReason {
    PRODUCT_MISMATCH,
    AI_ARTIFACT,
    WRONG_FACT,
    TEXT_ERROR,
    STYLE,
    LOW_QUALITY,
    POLICY
}
