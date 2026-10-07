package com.kiano.platform.queue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Claims queued tasks atomically via FOR UPDATE SKIP LOCKED, executes the
 * matching {@link TaskHandler} outside the claim statement, and persists the
 * outcome. Failures back off exponentially (30s * 2^(attempts-1)); exhausted
 * or non-retryable tasks are marked FAILED.
 */
@Component
public class TaskDispatcher {

    private static final int MAX_ERROR_LENGTH = 2000;
    private static final long BASE_BACKOFF_SECONDS = 30;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final QueueProperties properties;
    private final Map<String, TaskHandler> handlers = new HashMap<>();
    private final String workerId = UUID.randomUUID().toString();

    public TaskDispatcher(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            QueueProperties properties, List<TaskHandler> handlerList) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        for (TaskHandler handler : handlerList) {
            TaskHandler previous = handlers.put(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate task handler for type: " + handler.type());
            }
        }
    }

    /**
     * Polls the queue in the background; disabled via kiano.queue.enabled=false.
     */
    @Scheduled(fixedDelayString = "${kiano.queue.poll-interval}")
    public void scheduledPoll() {
        if (properties.isEnabled()) {
            pollOnce();
        }
    }

    /**
     * Reclaims expired leases, claims one task and runs it.
     * Returns true if a task was claimed (whatever the outcome), false when idle.
     */
    public boolean pollOnce() {
        reclaimExpiredLeases();
        ClaimedTask claimed = claim();
        if (claimed == null) {
            return false;
        }
        dispatch(claimed);
        return true;
    }

    private void reclaimExpiredLeases() {
        jdbcTemplate.update(
                "update platform_task set status='QUEUED', locked_by=null, locked_until=null "
                        + "where status='RUNNING' and locked_until < now()");
    }

    private ClaimedTask claim() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<ClaimedTask> rows = jdbcTemplate.query(
                "update platform_task t set status='RUNNING', attempts=t.attempts+1, locked_by=?, "
                        + "locked_until=?, started_at=? where t.id = (select id from platform_task "
                        + "where status='QUEUED' and run_after <= now() order by id for update skip locked limit 1) "
                        + "returning id, tenant_id, type, payload, attempts, max_attempts",
                (rs, rowNum) -> new ClaimedTask(rs.getLong("id"), rs.getLong("tenant_id"),
                        rs.getString("type"), rs.getString("payload"), rs.getInt("attempts"),
                        rs.getInt("max_attempts")),
                workerId, now.plus(properties.getLease()), now);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void dispatch(ClaimedTask claimed) {
        TaskHandler handler = handlers.get(claimed.type());
        if (handler == null) {
            fail(claimed, "no handler for type " + claimed.type());
            return;
        }
        try {
            TaskContext ctx = new TaskContext(claimed.id(), claimed.tenantId(),
                    objectMapper.readTree(claimed.payload()), claimed.attempts());
            Object result = handler.handle(ctx);
            succeed(claimed, result);
        } catch (NonRetryableTaskException ex) {
            fail(claimed, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (claimed.attempts() >= claimed.maxAttempts()) {
                fail(claimed, message);
            } else {
                requeueWithBackoff(claimed, message);
            }
        }
    }

    private void succeed(ClaimedTask claimed, Object result) {
        String resultJson = objectMapper.writeValueAsString(result == null ? Map.of() : result);
        jdbcTemplate.update(
                "update platform_task set status='SUCCEEDED', result=?::jsonb, finished_at=?, "
                        + "locked_by=null, locked_until=null where id=?",
                resultJson, OffsetDateTime.now(ZoneOffset.UTC), claimed.id());
    }

    private void fail(ClaimedTask claimed, String message) {
        jdbcTemplate.update(
                "update platform_task set status='FAILED', last_error=?, finished_at=?, "
                        + "locked_by=null, locked_until=null where id=?",
                truncate(message), OffsetDateTime.now(ZoneOffset.UTC), claimed.id());
    }

    private void requeueWithBackoff(ClaimedTask claimed, String message) {
        long backoffSeconds = BASE_BACKOFF_SECONDS * (1L << (claimed.attempts() - 1));
        jdbcTemplate.update(
                "update platform_task set status='QUEUED', run_after = now() + (? * interval '1 second'), "
                        + "last_error=?, locked_by=null, locked_until=null where id=?",
                backoffSeconds, truncate(message), claimed.id());
    }

    private String truncate(String message) {
        return message == null ? null : (message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message);
    }

    /**
     * A claimed task row as returned by the claim update.
     */
    private record ClaimedTask(long id, long tenantId, String type, String payload, int attempts, int maxAttempts) {
    }
}
