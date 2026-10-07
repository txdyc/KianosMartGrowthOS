package com.kiano.platform.integration;

import java.time.Instant;

/**
 * Integration metadata without credentials.
 */
public record StoredIntegration(long id, long tenantId, String provider, String accountRef, String status,
        Instant lastSyncAt, Instant updatedAt) {
}
