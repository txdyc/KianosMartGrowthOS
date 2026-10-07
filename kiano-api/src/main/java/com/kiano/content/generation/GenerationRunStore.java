package com.kiano.content.generation;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/**
 * generation_run persistence: one row per pipeline start, used for progress
 * display and the PIPELINE_RUNNING guard.
 */
@Component
public class GenerationRunStore {

    private final JdbcTemplate jdbcTemplate;

    public GenerationRunStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record GenerationRun(long id, long productId, String status, Instant createdAt,
            @Nullable Instant finishedAt) {
    }

    public long create(long tenantId, long productId, @Nullable Long createdBy) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into generation_run (tenant_id, product_id, kind, status, created_by) "
                            + "values (?, ?, 'IMAGE_SET', 'RUNNING', ?)",
                    new String[]{"id"});
            ps.setLong(1, tenantId);
            ps.setLong(2, productId);
            if (createdBy == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, createdBy);
            }
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<GenerationRun> latest(long tenantId, long productId) {
        return jdbcTemplate.query(
                "select * from generation_run where tenant_id=? and product_id=? order by id desc limit 1",
                mapper, tenantId, productId).stream().findFirst();
    }

    /**
     * Recomputes the run status: RUNNING while any job is unfinished, DONE when
     * every job succeeded, PARTIAL otherwise.
     */
    public void refreshStatus(long runId) {
        jdbcTemplate.update(
                "update generation_run r set status = case "
                        + "when exists (select 1 from generation_job j where j.run_id = r.id "
                        + "and j.status in ('QUEUED','LEASED','WAITING_EXECUTOR')) then 'RUNNING' "
                        + "when exists (select 1 from generation_job j where j.run_id = r.id "
                        + "and j.status <> 'SUCCEEDED') then 'PARTIAL' "
                        + "else 'DONE' end, "
                        + "finished_at = case when exists (select 1 from generation_job j where j.run_id = r.id "
                        + "and j.status in ('QUEUED','LEASED','WAITING_EXECUTOR')) then r.finished_at "
                        + "else coalesce(r.finished_at, now()) end "
                        + "where r.id = ?", runId);
    }

    public boolean hasActive(long tenantId, long productId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists (select 1 from generation_run where tenant_id=? and product_id=? "
                        + "and status='RUNNING')", Boolean.class, tenantId, productId));
    }

    private final RowMapper<GenerationRun> mapper = (rs, rowNum) -> new GenerationRun(
            rs.getLong("id"),
            rs.getLong("product_id"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            toInstant(rs.getTimestamp("finished_at")));

    private static @Nullable Instant toInstant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
