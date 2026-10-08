package com.kiano.content.asset;

/**
 * Published when a PAGE_MAIN asset is approved (inside the review
 * transaction). The fact-derivation listener uses it to enqueue the
 * PAGE_INFO render once facts are locked (Task 7).
 */
public record MainImageApprovedEvent(long tenantId, long productId, long assetId) {
}