package com.kiano.content.facts;

/**
 * Published when a fact sheet draft becomes LOCKED (inside the lock
 * transaction). Derived work - archiving stale assets, enqueueing copy and
 * INFO/SPEC renders - consumes it after commit (Task 7).
 */
public record FactLockedEvent(long tenantId, long productId, int version) {
}