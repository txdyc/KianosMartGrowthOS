package com.kiano.workerprotocol;

/**
 * Heartbeat result; a 409 LEASE_LOST carries the same information as ok=false.
 */
public record HeartbeatResponse(boolean ok) {
}
