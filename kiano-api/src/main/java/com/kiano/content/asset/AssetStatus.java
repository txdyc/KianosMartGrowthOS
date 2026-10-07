package com.kiano.content.asset;

/** Lifecycle of an asset. IN_REVIEW is the default landing state after generation. */
public enum AssetStatus {
    DRAFT,
    IN_REVIEW,
    APPROVED,
    REJECTED,
    PUBLISHED,
    STALE,
    ARCHIVED
}
