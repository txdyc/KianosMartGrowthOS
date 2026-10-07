package com.kiano.platform.queue;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * Read-only view of a platform_task row.
 */
public record TaskView(long id, String type, TaskStatus status, int attempts, JsonNode result, String lastError,
        Instant createdAt, Instant startedAt, Instant finishedAt) {
}
