package com.kiano.content.generation;

import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobStep;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * generation_job persistence: SKIP LOCKED leases (10 minutes), heartbeats,
 * executor-waiting that refunds attempts, exponential backoff and a lease
 * reaper. All terminal transitions write finished_at.
 */
@Component
public class GenerationJobStore {

    private static final int MAX_ERROR_LENGTH = 2000;
    private static final long BASE_BACKOFF_SECONDS = 30;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final RowMapper<GenerationJob> mapper = new GenerationJobMapper();

    public GenerationJobStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public long create(long tenantId, long productId, long runId, JobStep step, String variant,
            Object input, List<Long> parentJobIds) {
        String inputJson = objectMapper.writeValueAsString(input == null ? Map.of() : input);
        List<Long> parents = parentJobIds == null ? List.of() : parentJobIds;
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into generation_job (tenant_id, product_id, run_id, step, asset_spec_code, variant, "
                            + "executor, input_json, parent_job_ids, status, attempts, max_attempts) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'QUEUED', 0, 3)",
                    new String[]{"id"});
            ps.setLong(1, tenantId);
            ps.setLong(2, productId);
            ps.setLong(3, runId);
            ps.setString(4, step.name());
            ps.setString(5, step.assetSpecCode());
            ps.setString(6, variant);
            ps.setString(7, step.executor().name());
            ps.setString(8, inputJson);
            ps.setArray(9, con.createArrayOf("bigint", parents.toArray(new Long[0])));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** Atomically claims one QUEUED job for a worker with the given capabilities. */
    public Optional<GenerationJob> lease(String workerId, Set<ExecutorType> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return Optional.empty();
        }
        String placeholders = capabilities.stream().map(c -> "?").collect(Collectors.joining(","));
        String sql = "update generation_job j set status='LEASED', attempts=j.attempts+1, lease_owner=?, "
                + "lease_expires_at=now() + interval '10 minutes', heartbeat_at=now(), "
                + "started_at=coalesce(j.started_at, now()) "
                + "where j.id = (select id from generation_job where status='QUEUED' and run_after <= now() "
                + "and executor in (" + placeholders + ") order by id for update skip locked limit 1) "
                + "returning *";
        Object[] params = new Object[capabilities.size() + 1];
        params[0] = workerId;
        int i = 1;
        for (ExecutorType capability : capabilities) {
            params[i++] = capability.name();
        }
        return jdbcTemplate.query(sql, mapper, params).stream().findFirst();
    }

    /** Extends the lease by 10 minutes; false when the job is no longer held by this worker. */
    public boolean heartbeat(long jobId, String workerId) {
        return jdbcTemplate.update(
                "update generation_job set lease_expires_at = now() + interval '10 minutes', heartbeat_at = now() "
                        + "where id = ? and status = 'LEASED' and lease_owner = ? and lease_expires_at > now()",
                jobId, workerId) > 0;
    }

    /** Marks a leased job SUCCEEDED; empty when the lease was lost or reaped. */
    public Optional<GenerationJob> complete(long jobId, String workerId, Object output, double gpuSeconds) {
        String outputJson = objectMapper.writeValueAsString(output == null ? Map.of() : output);
        return jdbcTemplate.query(
                "update generation_job set status='SUCCEEDED', output_json=?::jsonb, gpu_seconds=?, "
                        + "lease_owner=null, lease_expires_at=null, heartbeat_at=null, finished_at=now() "
                        + "where id=? and status='LEASED' and lease_owner=? and lease_expires_at > now() "
                        + "returning *",
                mapper, outputJson, gpuSeconds, jobId, workerId).stream().findFirst();
    }

    /**
     * Fails a leased job. Executor-unavailable moves it to WAITING_EXECUTOR and
     * refunds the attempt; retryable failures back off (30s * 2^(attempts-1));
     * everything else (and exhausted retries) is FAILED.
     */
    @Transactional
    public Optional<GenerationJob> fail(long jobId, String workerId, String error, boolean retryable,
            boolean executorUnavailable) {
        List<GenerationJob> held = jdbcTemplate.query(
                "select * from generation_job where id=? and status='LEASED' and lease_owner=? "
                        + "and lease_expires_at > now() for update",
                mapper, jobId, workerId);
        if (held.isEmpty()) {
            return Optional.empty();
        }
        GenerationJob job = held.get(0);
        String truncated = truncate(error);
        if (executorUnavailable) {
            jdbcTemplate.update(
                    "update generation_job set status='WAITING_EXECUTOR', attempts=attempts-1, error=?, "
                            + "lease_owner=null, lease_expires_at=null, heartbeat_at=null where id=?",
                    truncated, jobId);
        } else if (retryable && job.attempts() < job.maxAttempts()) {
            long backoffSeconds = BASE_BACKOFF_SECONDS * (1L << (job.attempts() - 1));
            jdbcTemplate.update(
                    "update generation_job set status='QUEUED', run_after = now() + (? * interval '1 second'), "
                            + "error=?, lease_owner=null, lease_expires_at=null, heartbeat_at=null where id=?",
                    backoffSeconds, truncated, jobId);
        } else {
            jdbcTemplate.update(
                    "update generation_job set status='FAILED', finished_at=now(), error=?, "
                            + "lease_owner=null, lease_expires_at=null, heartbeat_at=null where id=?",
                    truncated, jobId);
        }
        return find(job.tenantId(), jobId);
    }

    /** LEASED jobs whose lease expired: FAILED when attempts are exhausted, else QUEUED again. */
    public int reapExpiredLeases() {
        return jdbcTemplate.update(
                "update generation_job j set "
                        + "status = case when j.attempts >= j.max_attempts then 'FAILED' else 'QUEUED' end, "
                        + "finished_at = case when j.attempts >= j.max_attempts then now() else null end, "
                        + "error = case when j.attempts >= j.max_attempts then coalesce(j.error, 'lease expired') "
                        + "else j.error end, "
                        + "lease_owner = null, lease_expires_at = null, heartbeat_at = null "
                        + "where j.status = 'LEASED' and j.lease_expires_at < now()");
    }

    /** QUEUED jobs of this executor wait until the executor comes back. */
    public void markWaiting(ExecutorType executor) {
        jdbcTemplate.update(
                "update generation_job set status='WAITING_EXECUTOR' where status='QUEUED' and executor=?",
                executor.name());
    }

    /** WAITING_EXECUTOR jobs of this executor become QUEUED again; returns how many. */
    public int releaseWaiting(ExecutorType executor) {
        return jdbcTemplate.update(
                "update generation_job set status='QUEUED' where status='WAITING_EXECUTOR' and executor=?",
                executor.name());
    }

    /** Retries a FAILED job: back to QUEUED with attempts reset. */
    public GenerationJob retry(long tenantId, long jobId) {
        GenerationJob job = find(tenantId, jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND",
                        "Generation job " + jobId + " does not exist"));
        if (job.status() != JobStatus.FAILED) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_NOT_FAILED",
                    "Only FAILED jobs can be retried, job " + jobId + " is " + job.status());
        }
        jdbcTemplate.update(
                "update generation_job set status='QUEUED', attempts=0, error=null, run_after=now(), "
                        + "finished_at=null where id=? and status='FAILED'", jobId);
        return find(tenantId, jobId).orElseThrow();
    }

    public List<GenerationJob> findByRun(long tenantId, long runId) {
        return jdbcTemplate.query(
                "select * from generation_job where tenant_id=? and run_id=? order by id", mapper, tenantId, runId);
    }

    public Optional<GenerationJob> find(long tenantId, long jobId) {
        return jdbcTemplate.query(
                "select * from generation_job where tenant_id=? and id=?", mapper, tenantId, jobId)
                .stream().findFirst();
    }

    /** Tenant-less lookup for worker-facing endpoints (the api trusts job ids it issued). */
    public Optional<GenerationJob> findById(long jobId) {
        return jdbcTemplate.query("select * from generation_job where id=?", mapper, jobId)
                .stream().findFirst();
    }

    private static String truncate(@Nullable String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }

    private class GenerationJobMapper implements RowMapper<GenerationJob> {
        @Override
        public GenerationJob mapRow(ResultSet rs, int rowNum) throws SQLException {
            java.sql.Array parentArray = rs.getArray("parent_job_ids");
            List<Long> parents = new ArrayList<>();
            if (parentArray != null) {
                for (Object value : (Object[]) parentArray.getArray()) {
                    parents.add(((Number) value).longValue());
                }
            }
            String outputJson = rs.getString("output_json");
            Double gpuSeconds = rs.getObject("gpu_seconds") == null ? null
                    : rs.getBigDecimal("gpu_seconds").doubleValue();
            java.sql.Timestamp leaseExpires = rs.getTimestamp("lease_expires_at");
            java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
            java.sql.Timestamp finishedAt = rs.getTimestamp("finished_at");
            return new GenerationJob(
                    rs.getLong("id"),
                    rs.getLong("tenant_id"),
                    rs.getLong("product_id"),
                    rs.getLong("run_id"),
                    JobStep.valueOf(rs.getString("step")),
                    rs.getString("variant"),
                    ExecutorType.valueOf(rs.getString("executor")),
                    readJson(rs.getString("input_json")),
                    List.copyOf(parents),
                    JobStatus.valueOf(rs.getString("status")),
                    rs.getInt("attempts"),
                    rs.getInt("max_attempts"),
                    rs.getString("lease_owner"),
                    leaseExpires == null ? null : leaseExpires.toInstant(),
                    outputJson == null ? null : readJson(outputJson),
                    gpuSeconds,
                    rs.getString("error"),
                    createdAt == null ? null : createdAt.toInstant(),
                    finishedAt == null ? null : finishedAt.toInstant());
        }

        private JsonNode readJson(@Nullable String json) {
            return json == null ? objectMapper.readTree("{}") : objectMapper.readTree(json);
        }
    }
}
