package com.kiano.content.policy;

/**
 * PolicyPublished when a new store policy version is saved (inside the save
 * transaction). Long-copy assets are re-rendered after commit (Task 12).
 */
public record PolicyChangedEvent(long tenantId, int version) {
}