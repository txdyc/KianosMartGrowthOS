package com.kiano.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.platform.llm.LlmCallRecorder.LlmCallRow;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * LlmCallRecorder writes one llm_call row per call with tokens, cost and
 * status; rows are queryable per tenant for the §22 cost ledger.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LlmCallRecorderTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LlmCallRecorder recorder;

    private long tenantId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from llm_call");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
    }

    @Test
    void record_insertsRowWithTokensCostAndStatus() {
        long id = recorder.record(new LlmCallRow(tenantId, LlmPurpose.FACT_DRAFT,
                "claude-opus-5-5", "OK", 1200, 350, 800, 50,
                new BigDecimal("0.011960"), 812, "end_turn", null));

        assertThat(id).isPositive();
        assertThat(jdbcTemplate.queryForObject("select status from llm_call where id = ?",
                String.class, id)).isEqualTo("OK");
        assertThat(jdbcTemplate.queryForObject("select purpose from llm_call where id = ?",
                String.class, id)).isEqualTo("FACT_DRAFT");
        assertThat(jdbcTemplate.queryForObject("select input_tokens from llm_call where id = ?",
                Long.class, id)).isEqualTo(1200);
        assertThat(jdbcTemplate.queryForObject("select output_tokens from llm_call where id = ?",
                Long.class, id)).isEqualTo(350);
        assertThat(jdbcTemplate.queryForObject("select cache_read_tokens from llm_call where id = ?",
                Long.class, id)).isEqualTo(800);
        assertThat(jdbcTemplate.queryForObject("select cache_write_tokens from llm_call where id = ?",
                Long.class, id)).isEqualTo(50);
        assertThat(jdbcTemplate.queryForObject("select cost_usd from llm_call where id = ?",
                BigDecimal.class, id)).isEqualByComparingTo(new BigDecimal("0.011960"));
        assertThat(jdbcTemplate.queryForObject("select stop_reason from llm_call where id = ?",
                String.class, id)).isEqualTo("end_turn");
        assertThat(jdbcTemplate.queryForObject("select error from llm_call where id = ?",
                String.class, id)).isNull();
    }

    @Test
    void record_failureRow_keepsErrorAndNullCost() {
        long id = recorder.record(new LlmCallRow(tenantId, LlmPurpose.COPY,
                "claude-opus-5-5", "ERROR", 0, 0, 0, 0, null, 3,
                null, "LLM_UNAVAILABLE: boom"));

        assertThat(jdbcTemplate.queryForObject("select status from llm_call where id = ?",
                String.class, id)).isEqualTo("ERROR");
        assertThat(jdbcTemplate.queryForObject("select error from llm_call where id = ?",
                String.class, id)).contains("boom");
        assertThat(jdbcTemplate.queryForObject("select cost_usd from llm_call where id = ?",
                Object.class, id)).isNull();
    }

    @Test
    void rowsAreIndexedByTenantDesc() {
        recorder.record(new LlmCallRow(tenantId, LlmPurpose.FACT_DRAFT, "m", "OK",
                1, 1, 0, 0, null, 1, null, null));
        recorder.record(new LlmCallRow(tenantId, LlmPurpose.COPY, "m", "OK",
                1, 1, 0, 0, null, 1, null, null));

        assertThat(jdbcTemplate.queryForList(
                "select purpose from llm_call where tenant_id = ? order by created_at desc",
                String.class, tenantId)).containsExactly("COPY", "FACT_DRAFT");
    }
}