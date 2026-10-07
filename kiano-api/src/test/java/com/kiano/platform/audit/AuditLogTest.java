package com.kiano.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuditLogTest {

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void record_persistsJsonBeforeAfter() {
        Long tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'", Long.class);
        auditLog.record(new AuditEntry(tenantId, ActorType.USER, "1", "TEST_ACTION", "product", "42",
                Map.of("old", 1), Map.of("contentTier", "HERO"), "test reason", "test source"));
        String tier = jdbcTemplate.queryForObject(
                "select after_json->>'contentTier' from audit_log order by id desc limit 1", String.class);
        assertThat(tier).isEqualTo("HERO");
        String before = jdbcTemplate.queryForObject(
                "select before_json->>'old' from audit_log order by id desc limit 1", String.class);
        assertThat(before).isEqualTo("1");
    }

    @Test
    void record_allowsNullBeforeAfter() {
        Long tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'", Long.class);
        auditLog.record(new AuditEntry(tenantId, ActorType.SYSTEM, null, "TEST_NULLS", null, null,
                null, null, null, null));
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'TEST_NULLS' and before_json is null and after_json is null",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
