package com.kiano.platform.audit;

/**
 * One audit event. {@code before}/{@code after} are serialized to jsonb and may be null.
 */
public record AuditEntry(long tenantId, ActorType actorType, String actorId, String action, String targetType,
        String targetId, Object before, Object after, String reason, String source) {
}
