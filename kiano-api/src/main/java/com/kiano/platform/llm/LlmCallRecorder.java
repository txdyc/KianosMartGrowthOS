package com.kiano.platform.llm;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Appends one row to llm_call for every gateway call, success or failure, so
 * token usage and cost are auditable per tenant (v1.2 §22). The gateway
 * computes cost; this class persists. The insert happens after the call
 * completes, outside the caller's transaction, so a later failure never
 * hides the ledger entry.
 */
@Component
public class LlmCallRecorder {

    private final JdbcTemplate jdbcTemplate;

    public LlmCallRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record LlmCallRow(long tenantId, LlmPurpose purpose, String model, String status,
            long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
            @Nullable BigDecimal costUsd, int latencyMs, @Nullable String stopReason,
            @Nullable String error, @Nullable String provider) {
    }

    /** Inserts the row and returns the new llm_call id. */
    public long record(LlmCallRow row) {
        Long id = jdbcTemplate.queryForObject(
                "insert into llm_call (tenant_id, purpose, model, input_tokens, output_tokens, "
                        + "cache_read_tokens, cache_write_tokens, cost_usd, latency_ms, status, "
                        + "stop_reason, error, provider) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "?, ?, ?) returning id",
                Long.class, row.tenantId(), row.purpose().name(), row.model(), row.inputTokens(),
                row.outputTokens(), row.cacheReadTokens(), row.cacheWriteTokens(), row.costUsd(),
                row.latencyMs(), row.status(), row.stopReason(), row.error(), row.provider());
        return id;
    }
}