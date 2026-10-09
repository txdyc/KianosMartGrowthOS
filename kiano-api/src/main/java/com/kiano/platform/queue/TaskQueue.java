package com.kiano.platform.queue;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Enqueue API for the PostgreSQL-backed task queue. The partial unique index
 * platform_task_dedupe rejects duplicates while a task with the same
 * (tenant, type, dedupe_key) is QUEUED or RUNNING.
 */
@Component
public class TaskQueue {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TaskQueue(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Inserts a task; returns empty if an active task with the same dedupe key exists.
     * The duplicate is skipped with ON CONFLICT DO NOTHING rather than a caught
     * unique violation: in PostgreSQL a failed statement aborts the caller's
     * whole transaction, which would silently undo the caller's other writes.
     */
    public Optional<Long> enqueue(long tenantId, String type, Object payload, String dedupeKey) {
        String payloadJson = objectMapper.writeValueAsString(payload == null ? java.util.Map.of() : payload);
        List<Long> ids = jdbcTemplate.queryForList(
                "insert into platform_task (tenant_id, type, payload, dedupe_key, status) "
                        + "values (?, ?, ?::jsonb, ?, 'QUEUED') on conflict do nothing returning id",
                Long.class, tenantId, type, payloadJson, dedupeKey);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }

    /**
     * Latest task of the given type for the tenant.
     */
    public Optional<TaskView> latest(long tenantId, String type) {
        List<TaskView> rows = jdbcTemplate.query(
                "select id, type, status, attempts, result, last_error, created_at, started_at, finished_at "
                        + "from platform_task where tenant_id = ? and type = ? order by id desc limit 1",
                this::mapView, tenantId, type);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Latest task of the given type whose payload has {@code payloadField = value}
     * (e.g. the newest AD_RENDER of one product).
     */
    public Optional<TaskView> latestFor(long tenantId, String type, String payloadField, long value) {
        List<TaskView> rows = jdbcTemplate.query(
                "select id, type, status, attempts, result, last_error, created_at, started_at, finished_at "
                        + "from platform_task where tenant_id = ? and type = ? "
                        + "and payload ->> ? = ? order by id desc limit 1",
                this::mapView, tenantId, type, payloadField, String.valueOf(value));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private TaskView mapView(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String result = rs.getString("result");
        return new TaskView(rs.getLong("id"), rs.getString("type"),
                TaskStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
                result == null ? null : objectMapper.readTree(result), rs.getString("last_error"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null
                        : rs.getTimestamp("finished_at").toInstant());
    }
}
