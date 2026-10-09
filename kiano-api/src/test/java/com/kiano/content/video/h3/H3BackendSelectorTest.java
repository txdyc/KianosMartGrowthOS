package com.kiano.content.video.h3;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.content.workflow.WorkflowRegistry;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobStep;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H3 backend selection (C5 T8b): LOCAL_FIRST prefers an active H3_I2V workflow
 * and falls back to the API when it is enabled; LOCAL_ONLY, API_ONLY and OFF
 * narrow that; escalation from local to API happens only in LOCAL_FIRST, only
 * after a local failure and only while the daily budget has room.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class H3BackendSelectorTest {

    @Autowired
    private H3BackendSelector selector;

    @Autowired
    private WorkflowRegistry workflows;

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private GenerationRunStore runs;

    @Autowired
    private H3Properties properties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantId;
    private long productId;
    private long runId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from comfy_workflow");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        productId = insertProduct("H3-SELECTOR-" + System.nanoTime());
        runId = runs.create(tenantId, productId, null);
        properties.setBackend(H3Properties.Mode.LOCAL_FIRST);
        properties.setRequestSeconds(5);
        properties.getApi().setEnabled(true);
        properties.getApi().setPricePerSecondUsd(new BigDecimal("2")); // 5 s → 10 USD
        properties.getApi().setDailyBudgetUsd(new BigDecimal("10"));
    }

    /** The properties bean is shared by the cached context: restore the defaults. */
    @AfterEach
    void restoreProperties() {
        properties.setBackend(H3Properties.Mode.LOCAL_FIRST);
        properties.setRequestSeconds(5);
        properties.getApi().setEnabled(false);
        properties.getApi().setPricePerSecondUsd(BigDecimal.ZERO);
        properties.getApi().setDailyBudgetUsd(new BigDecimal("10"));
    }

    @Test
    void localFirst_prefersActiveLocalWorkflow_elseApiIfEnabled_elseEmpty() {
        assertThat(selector.initial(tenantId)).contains(H3Backend.API);

        activateH3Workflow();

        assertThat(selector.initial(tenantId)).contains(H3Backend.LOCAL);

        properties.getApi().setEnabled(false);
        jdbcTemplate.update("update comfy_workflow set status = 'RETIRED' where tenant_id = ?", tenantId);

        assertThat(selector.initial(tenantId)).isEmpty();
    }

    @Test
    void next_localFailed_escalatesToApiOnlyInLocalFirstWithBudget() {
        assertThat(selector.next(tenantId, H3Backend.LOCAL)).contains(H3Backend.API);
        // an API failure never escalates further
        assertThat(selector.next(tenantId, H3Backend.API)).isEmpty();

        // an earlier clip today used 6 of the 10 USD budget; the next clip needs 10
        seedSucceededCost(new BigDecimal("6"));

        assertThat(selector.next(tenantId, H3Backend.LOCAL)).isEmpty();

        properties.setBackend(H3Properties.Mode.LOCAL_ONLY);
        assertThat(selector.next(tenantId, H3Backend.LOCAL)).isEmpty();
    }

    @Test
    void localOnly_apiOnly_off_modes() {
        properties.setBackend(H3Properties.Mode.LOCAL_ONLY);
        assertThat(selector.initial(tenantId)).isEmpty();
        activateH3Workflow();
        assertThat(selector.initial(tenantId)).contains(H3Backend.LOCAL);

        properties.setBackend(H3Properties.Mode.API_ONLY);
        assertThat(selector.initial(tenantId)).contains(H3Backend.API);
        properties.getApi().setEnabled(false);
        assertThat(selector.initial(tenantId)).isEmpty();

        properties.setBackend(H3Properties.Mode.OFF);
        properties.getApi().setEnabled(true);
        assertThat(selector.initial(tenantId)).isEmpty();
    }

    private void activateH3Workflow() {
        long id = workflows.register(tenantId, fixture("workflow.json"), fixture("manifest.json"), null).id();
        workflows.activate(tenantId, id);
    }

    private void seedSucceededCost(BigDecimal cost) {
        long jobId = jobs.create(tenantId, productId, runId, JobStep.H3_CLIP_API, "demo",
                Map.of("outputs", Map.of("clip", "t1/gen/clip.mp4")), List.of());
        assertThat(jobs.lease("seed", Set.of(ExecutorType.H3))).isPresent();
        jobs.complete(jobId, "seed", Map.of(), 0, cost);
    }

    private long insertProduct(String sku) {
        Long storeId = jdbcTemplate.query(
                "select id from store where tenant_id = ? order by id limit 1",
                (rs, rowNum) -> rs.getLong("id"), tenantId).stream().findFirst().orElseGet(
                        () -> jdbcTemplate.queryForObject(
                                "insert into store (tenant_id, platform, base_url) "
                                        + "values (?, 'woocommerce', 'http://woo.test') returning id",
                                Long.class, tenantId));
        return jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, ?, 'simple', ?, 'H3 selector test', 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), sku);
    }

    private static String fixture(String file) {
        try (InputStream in = H3BackendSelectorTest.class
                .getResourceAsStream("/workflows/h3_i2v_fixture/" + file)) {
            if (in == null) {
                throw new IOException("fixture not found: " + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}