package com.kiano.platform.audit;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Persists an {@link AuditEntry}. The insert participates in the caller's transaction.
 */
@Service
public class AuditLog {

    private final AuditLogMapper mapper;
    private final ObjectMapper objectMapper;

    public AuditLog(AuditLogMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void record(AuditEntry entry) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setTenantId(entry.tenantId());
        entity.setActorType(entry.actorType().name());
        entity.setActorId(entry.actorId());
        entity.setAction(entry.action());
        entity.setTargetType(entry.targetType());
        entity.setTargetId(entry.targetId());
        entity.setBeforeJson(toJson(entry.before()));
        entity.setAfterJson(toJson(entry.after()));
        entity.setReason(entry.reason());
        entity.setSource(entry.source());
        mapper.insert(entity);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        return objectMapper.writeValueAsString(value);
    }
}
