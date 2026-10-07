package com.kiano.platform.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class IntegrationStoreTest {

    record WooCreds(String site, String user, String password) {
    }

    @Autowired
    private IntegrationStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantId;

    @BeforeEach
    void clean() {
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'", Long.class);
        jdbcTemplate.update("delete from integration");
    }

    @Test
    void save_thenReadCredentials() {
        store.save(tenantId, "woocommerce", "kianosmart",
                new WooCreds("http://localhost:8080", "admin", "app pass"));
        Optional<StoredIntegration> stored = store.find(tenantId, "woocommerce");
        assertThat(stored).isPresent();
        WooCreds creds = store.credentials(stored.get(), WooCreds.class);
        assertThat(creds.password()).isEqualTo("app pass");
        assertThat(creds.site()).isEqualTo("http://localhost:8080");
    }

    @Test
    void storedColumn_hasNoPlaintext() {
        store.save(tenantId, "woocommerce", "kianosmart",
                new WooCreds("http://localhost:8080", "admin", "app pass"));
        String encrypted = jdbcTemplate.queryForObject(
                "select credentials_encrypted from integration where tenant_id = ? and provider = 'woocommerce'",
                String.class, tenantId);
        assertThat(encrypted).startsWith("v1:");
        assertThat(encrypted).doesNotContain("app pass");
    }

    @Test
    void saveTwice_upsertsSingleRow() {
        store.save(tenantId, "woocommerce", "kianosmart",
                new WooCreds("http://localhost:8080", "admin", "first"));
        store.save(tenantId, "woocommerce", "kianosmart",
                new WooCreds("http://localhost:8080", "admin", "second"));
        Integer count = jdbcTemplate.queryForObject("select count(*) from integration", Integer.class);
        assertThat(count).isEqualTo(1);
        WooCreds creds = store.credentials(store.find(tenantId, "woocommerce").orElseThrow(), WooCreds.class);
        assertThat(creds.password()).isEqualTo("second");
    }

    @Test
    void findAllActive_returnsOnlyActive() {
        store.save(tenantId, "woocommerce", "kianosmart",
                new WooCreds("http://localhost:8080", "admin", "app pass"));
        store.save(tenantId, "other", "kianosmart", new WooCreds("http://x", "u", "p"));
        assertThat(store.findAllActive("woocommerce")).hasSize(1);
        assertThat(store.findAllActive("nothing")).isEmpty();
    }

    @Test
    void markSynced_updatesTimestamp() {
        store.save(tenantId, "woocommerce", "kianosmart",
                new WooCreds("http://localhost:8080", "admin", "app pass"));
        java.time.Instant at = java.time.Instant.parse("2026-10-07T10:15:30Z");
        store.markSynced(tenantId, "woocommerce", at);
        StoredIntegration stored = store.find(tenantId, "woocommerce").orElseThrow();
        assertThat(stored.lastSyncAt()).isEqualTo(at);
    }
}
