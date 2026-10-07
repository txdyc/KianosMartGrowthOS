package com.kiano.workerprotocol;

import tools.jackson.databind.JsonNode;

/**
 * The job handed to a worker; input_json carries everything the executor needs
 * (workflow, params, source pointers).
 */
public record JobPayload(long id, JobStep step, String variant, ExecutorType executor, JsonNode input) {
}
