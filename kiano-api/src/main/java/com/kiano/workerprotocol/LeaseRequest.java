package com.kiano.workerprotocol;

import java.util.Set;

/**
 * Worker request for one job. GPU work is single-concurrency: max is always
 * treated as 1.
 */
public record LeaseRequest(String workerId, Set<ExecutorType> capabilities, Set<ExecutorType> unavailable,
        int max) {
}
