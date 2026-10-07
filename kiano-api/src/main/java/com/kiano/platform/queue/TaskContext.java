package com.kiano.platform.queue;

import tools.jackson.databind.JsonNode;

/**
 * Input handed to a {@link TaskHandler}. {@code attempt} starts at 1.
 */
public record TaskContext(long taskId, long tenantId, JsonNode payload, int attempt) {
}
