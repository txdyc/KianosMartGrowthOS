package com.kiano.platform.queue;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
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
     */
    public Optional<Long> enqueue(long tenantId, String type, Object payload, String dedupeKey) {
        String payloadJson = objectMapper.writeValueAsString(payload == null ? java.util.Map.of() : payload);
        try {
            Long id = jdbcTemplate.queryForObject(
                    "insert into platform_task (tenant_id, type, payload, dedupe_key, status) "
                            + "values (?, ?, ?::jsonb, ?, 'QUEUED') returning id",
                    Long.class, tenantId, type, payloadJson, dedupeKey == null ? null : dedupeKey);
            return Optional.ofNullable(id);
        } catch (DuplicateKeyException ex) {
            return Optional.empty();
        }
    }

    /**
     * Latest task of the given type for the tenant.
     */
    public Optional<TaskView> latest(long tenantId, String type) {
        List<TaskView> rows = jdbcTemplate.query(
                "select id, type, status, attempts, result, last_error, created_at, started_at, finished_at "
                        + "from platform_task where tenant_id = ? and type = ? order by id desc limit 1",
                (rs, rowNum) -> {
                    String result = rs.getString("result");
                    return new TaskView(rs.getLong("id"), rs.getString("type"),
                            TaskStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
                            result == null ? null : objectMapper.readTree(result), rs.getString("last_error"),
                            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                            rs.getTimestamp("finished_at") == null ? null
                                    : rs.getTimestamp("finished_at").toInstant());
                }, tenantId, type);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
