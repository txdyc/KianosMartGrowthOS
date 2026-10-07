package com.kiano.content.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Template registry: the classpath v1 baselines are bootstrapped as APPROVED
 * exactly once per tenant, and a changed body for an existing version fails
 * startup because published template versions are immutable.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TemplateRegistryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TemplateBootstrap bootstrap;

    @Autowired
    private TemplateRegistry registry;

    private long tenantId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from template");
        tenantId = jdbcTemplate.queryForObject(
                "select id from tenant where slug = 'kianosmart'", Long.class);
    }

    @Test
    void bootstrap_registersV1Approved_once() {
        bootstrap.bootstrapAll();

        assertThat(registry.latestApproved(tenantId, "PAGE_INFO")).hasValueSatisfying(t -> {
            assertThat(t.getVersion()).isEqualTo(1);
            assertThat(t.getKind()).isEqualTo("INFOGRAPHIC");
            assertThat(t.getStatus()).isEqualTo("APPROVED");
            assertThat(t.getBody()).contains("benefits");
        });
        assertThat(registry.latestApproved(tenantId, "PAGE_SPEC")).hasValueSatisfying(t -> {
            assertThat(t.getVersion()).isEqualTo(1);
            assertThat(t.getKind()).isEqualTo("SPEC");
            assertThat(t.getStatus()).isEqualTo("APPROVED");
            assertThat(t.getBody()).contains("rows");
        });

        bootstrap.bootstrapAll();
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from template where tenant_id = ?", Integer.class, tenantId);
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void changedBodyForExistingVersion_failsStartup() {
        bootstrap.bootstrapAll();
        jdbcTemplate.update(
                "update template set body = 'tampered' where tenant_id = ? and code = 'PAGE_SPEC'",
                tenantId);

        assertThatThrownBy(bootstrap::bootstrapAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAGE_SPEC");
    }
}
