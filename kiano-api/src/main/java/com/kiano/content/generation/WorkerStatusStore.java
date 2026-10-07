package com.kiano.content.generation;

import com.kiano.workerprotocol.ExecutorType;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * worker_status persistence: worker liveness plus the executors each worker
 * can currently run. Global (no tenant) — infrastructure state.
 */
@Component
public class WorkerStatusStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WorkerStatusStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public record WorkerStatusView(String workerId, Instant lastSeenAt, Set<ExecutorType> capabilities,
            Set<ExecutorType> unavailable) {
    }

    public void touch(String workerId, Set<ExecutorType> capabilities, Set<ExecutorType> unavailable) {
        jdbcTemplate.update(
                "insert into worker_status (worker_id, last_seen_at, capabilities, unavailable) "
                        + "values (?, now(), ?::jsonb, ?::jsonb) "
                        + "on conflict (worker_id) do update set last_seen_at = now(), "
                        + "capabilities = excluded.capabilities, unavailable = excluded.unavailable",
                workerId, toJson(capabilities), toJson(unavailable));
    }

    public List<WorkerStatusView> list() {
        return jdbcTemplate.query("select * from worker_status order by worker_id", mapper);
    }

    /** True when at least one worker reported the executor available within the window. */
    public boolean isAvailable(ExecutorType executor, Duration window) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists (select 1 from worker_status where last_seen_at >= now() - (? * interval '1 millisecond') "
                        + "and capabilities @> ?::jsonb and not unavailable @> ?::jsonb)",
                Boolean.class, window.toMillis(),
                "[\"" + executor.name() + "\"]", "[\"" + executor.name() + "\"]"));
    }

    private String toJson(Set<ExecutorType> executors) {
        List<String> names = executors.stream().map(Enum::name).toList();
        return objectMapper.writeValueAsString(names);
    }

    private final RowMapper<WorkerStatusView> mapper = (rs, rowNum) -> {
        Timestamp lastSeen = rs.getTimestamp("last_seen_at");
        return new WorkerStatusView(
                rs.getString("worker_id"),
                lastSeen == null ? Instant.EPOCH : lastSeen.toInstant(),
                parse(rs.getString("capabilities")),
                parse(rs.getString("unavailable")));
    };

    private Set<ExecutorType> parse(@Nullable String json) {
        Set<ExecutorType> out = new HashSet<>();
        if (json == null) {
            return out;
        }
        JsonNode array = objectMapper.readTree(json);
        List<ExecutorType> values = new ArrayList<>();
        for (JsonNode node : array) {
            values.add(ExecutorType.valueOf(node.asString()));
        }
        out.addAll(values);
        return out;
    }
}
