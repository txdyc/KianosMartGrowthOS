package com.kiano.content.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.pipeline.PipelineService;
import com.kiano.content.policy.PolicySection;
import com.kiano.content.policy.PolicyService;
import com.kiano.content.policy.PolicyService.SectionText;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.workerprotocol.JobStep;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * BatchOrchestrator (C6 Task 3): a tick starts at most max-active-pipelines
 * pipelines and queues the capped fact drafts with batch priority, publishes
 * finished SKUs to staging as SYSTEM spaced by publish-interval, marks the
 * batch done when everything is DONE, and never duplicates work when two ticks
 * overlap (Review Focus 4) or lets one broken item stop the others (Review
 * Focus 3). Paused batches do nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BatchOrchestratorTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BatchOrchestrator orchestrator;

    @Autowired
    private BatchProperties properties;

    @Autowired
    private BatchService batches;

    @Autowired
    private PipelineService pipeline;

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private com.kiano.content.generation.GenerationRunStore runs;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private FactSheetService factSheetService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;
    private long storeId;
    private long userId;
    private CurrentUser user;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from content_batch_item");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from content_batch");
        jdbcTemplate.update("delete from platform_task");
        jdbcTemplate.update("delete from publication");
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from comfy_workflow");
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store_policy");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update("delete from audit_log where tenant_id = ?", tenantId);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'batch-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        user = new CurrentUser(userId, tenantId, Role.OPERATOR, "batch-op@example.test");
        activateWorkflow("CUTOUT");
        activateWorkflow("SCENE");
        properties.setMaxActivePipelines(3);
        properties.setMaxQueuedFactDrafts(5);
        properties.setPublishInterval(Duration.ofSeconds(10));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from content_batch_item");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from content_batch");
        jdbcTemplate.update("delete from platform_task");
    }

    @Test
    void tick_startsAtMostMaxActivePipelines_thenNextAsOneFinishes() {
        properties.setMaxActivePipelines(2);
        long first = readyProduct("MG-STD1");
        long second = readyProduct("MG-STD2");
        long third = readyProduct("MG-STD3");
        batches.create(user, "Spring", List.of(first, second, third), false);

        orchestrator.tickTenant(tenantId);

        List<BatchItemEntity> items = items();
        assertThat(items).filteredOn(item -> item.getImageRunId() != null).hasSize(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from generation_run where tenant_id = ?", Integer.class,
                tenantId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'BATCH_PIPELINE_STARTED' "
                        + "and tenant_id = ?", Integer.class, tenantId)).isEqualTo(2);

        // one pipeline finishes -> the next tick takes the waiting SKU
        long finishedRun = items.stream().map(BatchItemEntity::getImageRunId)
                .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        jdbcTemplate.update("update generation_run set status = 'DONE' where id = ?", finishedRun);

        orchestrator.tickTenant(tenantId);

        assertThat(items().stream().filter(item -> item.getImageRunId() != null)).hasSize(3);
    }

    @Test
    void tick_queuesFactDraftsWithBatchPriority_respectingCap() {
        properties.setMaxQueuedFactDrafts(2);
        long first = productWithFactSources("MG-STD1");
        long second = productWithFactSources("MG-STD2");
        long third = productWithFactSources("MG-STD3");
        batches.create(user, "Spring", List.of(first, second, third), false);

        orchestrator.tickTenant(tenantId);

        List<Map<String, Object>> tasks = jdbcTemplate.queryForList(
                "select payload, priority, dedupe_key from platform_task where tenant_id = ? "
                        + "and type = 'FACT_DRAFT' order by id", tenantId);
        assertThat(tasks).hasSize(2);
        assertThat(tasks).allSatisfy(task -> {
            assertThat(((Number) task.get("priority")).intValue()).isEqualTo(10);
            assertThat((String) task.get("dedupe_key")).startsWith("fact-draft:");
        });
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'BATCH_FACT_DRAFT_QUEUED' "
                        + "and tenant_id = ?", Integer.class, tenantId)).isEqualTo(2);
    }

    @Test
    void tick_allApproved_requestsStagingAsSystem_spacedByPublishInterval() {
        properties.setPublishInterval(Duration.ofSeconds(5));
        long first = publishableProduct("MG-STD1");
        long second = publishableProduct("MG-STD2");
        batches.create(user, "Spring", List.of(first, second), false);

        orchestrator.tickTenant(tenantId);

        List<Map<String, Object>> publications = jdbcTemplate.queryForList(
                "select id, product_id, environment, status, published_by from publication "
                        + "where tenant_id = ? order by id", tenantId);
        assertThat(publications).hasSize(2);
        assertThat(publications).allSatisfy(row -> {
            assertThat(row.get("environment")).isEqualTo("STAGING");
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("published_by")).isNull();
        });
        // the publish tasks are batch priority and spaced by publish-interval
        List<Timestamp> runAfters = jdbcTemplate.queryForList(
                "select run_after from platform_task where tenant_id = ? and type = 'PUBLISH_PRODUCT' "
                        + "order by id", Timestamp.class, tenantId);
        assertThat(runAfters).hasSize(2);
        assertThat(Duration.between(runAfters.get(0).toInstant(), runAfters.get(1).toInstant())
                .toSeconds()).isGreaterThanOrEqualTo(4);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'BATCH_STAGING_REQUESTED' "
                        + "and tenant_id = ?", Integer.class, tenantId)).isEqualTo(2);
        assertThat(items()).allSatisfy(item ->
                assertThat(item.getStagingPublicationId()).isNotNull());

        // the second tick does not publish again (idempotent)
        orchestrator.tickTenant(tenantId);
        assertThat(jdbcTemplate.queryForObject("select count(*) from publication where tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(2);
    }

    @Test
    void concurrentTicks_neverDoubleStartOrDoublePublish() throws Exception {
        long first = readyProduct("MG-STD1");
        long second = readyProduct("MG-STD2");
        batches.create(user, "Spring", List.of(first, second), false);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    start.await();
                    orchestrator.tickOnce();
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // exactly one pipeline per SKU, whatever the interleaving was
        assertThat(jdbcTemplate.queryForObject("select count(*) from generation_run "
                + "where tenant_id = ?", Integer.class, tenantId)).isEqualTo(2);
        assertThat(items()).allSatisfy(item -> assertThat(item.getImageRunId()).isNotNull());
    }

    @Test
    void oneItemThrows_othersStillProgress_itemBlockedInternal() {
        // the first SKU's COPY_LONG predates the policy -> the staging request
        // fails for it, while the second SKU publishes normally
        long broken = publishableProduct("MG-STD1", 0);
        long healthy = publishableProduct("MG-STD2");
        batches.create(user, "Spring", List.of(broken, healthy), false);

        orchestrator.tickTenant(tenantId);

        List<BatchItemEntity> items = items();
        BatchItemEntity brokenItem = items.stream()
                .filter(item -> item.getProductId() == broken).findFirst().orElseThrow();
        BatchItemEntity healthyItem = items.stream()
                .filter(item -> item.getProductId() == healthy).findFirst().orElseThrow();
        // the business error's own code says what to fix (here: re-render the copy)
        assertThat(brokenItem.getBlockedReason()).isEqualTo("COPY_POLICY_OUTDATED");
        assertThat(healthyItem.getStagingPublicationId()).isNotNull();
    }

    @Test
    void scheduledRound_oneItemsPublishError_doesNotRollBackTheOthers() {
        // tickOnce wraps the tenant round in a transaction (the production path):
        // the broken SKU's error must not poison the whole round
        long broken = publishableProduct("MG-STD1", 0);
        long healthy = publishableProduct("MG-STD2");
        long fresh = readyProduct("MG-STD3");
        batches.create(user, "Spring", List.of(broken, healthy, fresh), false);

        orchestrator.tickOnce();

        BatchItemEntity brokenItem = item(broken);
        assertThat(brokenItem.getBlockedReason()).isEqualTo("COPY_POLICY_OUTDATED");
        assertThat(item(healthy).getStagingPublicationId()).isNotNull();
        assertThat(item(fresh).getImageRunId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from publication "
                + "where tenant_id = ? and product_id = ?", Integer.class, tenantId, healthy))
                .isEqualTo(1);
    }

    @Test
    void scheduledRound_pipelineStartedOutsideTheBatch_isAdopted() {
        long product = readyProduct("MG-STD1");
        long other = readyProduct("MG-STD2");
        long manualRun = pipeline.start(user, product); // started from the product page
        batches.create(user, "Spring", List.of(product, other), false);

        orchestrator.tickOnce();

        assertThat(item(product).getImageRunId()).isEqualTo(manualRun);
        assertThat(item(other).getImageRunId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from generation_run "
                + "where tenant_id = ?", Integer.class, tenantId)).isEqualTo(2);
    }

    @Test
    void runningPipelinesAreCountedBeforeNewOnesStart() {
        properties.setMaxActivePipelines(3);
        List<Long> waiting = List.of(readyProduct("MG-W1"), readyProduct("MG-W2"),
                readyProduct("MG-W3"));
        List<Long> running = List.of(readyProduct("MG-R1"), readyProduct("MG-R2"),
                readyProduct("MG-R3"));
        List<Long> all = new java.util.ArrayList<>(waiting);
        all.addAll(running);
        batches.create(user, "Spring", all, false);
        // the three later items already run their pipelines (e.g. their shots came first)
        for (long productId : running) {
            long runId = runs.create(tenantId, productId, null);
            jdbcTemplate.update("update content_batch_item set image_run_id = ? "
                    + "where product_id = ?", runId, productId);
        }

        orchestrator.tickOnce();

        // three pipelines are already active: none of the earlier items may start
        assertThat(jdbcTemplate.queryForObject("select count(*) from generation_run "
                + "where tenant_id = ?", Integer.class, tenantId)).isEqualTo(3);
        for (long productId : waiting) {
            assertThat(item(productId).getImageRunId()).isNull();
        }
    }

    private BatchItemEntity item(long productId) {
        return items().stream().filter(row -> row.getProductId() == productId).findFirst()
                .orElseThrow();
    }

    @Test
    void pausedBatch_noActions() {
        long product = readyProduct("MG-STD1");
        long batchId = batches.create(user, "Spring", List.of(product), false).id();
        batches.pause(user, batchId);

        orchestrator.tickTenant(tenantId);

        assertThat(jdbcTemplate.queryForObject("select count(*) from generation_run "
                + "where tenant_id = ?", Integer.class, tenantId)).isZero();
        assertThat(items().get(0).getImageRunId()).isNull();
    }

    @Test
    void allDone_batchMarkedDone() {
        long first = publishableProduct("MG-STD1");
        long second = publishableProduct("MG-STD2");
        long batchId = batches.create(user, "Spring", List.of(first, second), false).id();
        for (long productId : List.of(first, second)) {
            jdbcTemplate.update("insert into publication (tenant_id, product_id, environment, "
                    + "target, asset_ids, uploaded_media_ids, archived_asset_ids, status) "
                    + "values (?, ?, 'PRODUCTION', 'WOO_PRODUCT', '[]'::jsonb, '[]'::jsonb, "
                    + "'[]'::jsonb, 'APPLIED')", tenantId, productId);
        }

        orchestrator.tickTenant(tenantId);

        assertThat(jdbcTemplate.queryForObject("select status from content_batch where id = ?",
                String.class, batchId)).isEqualTo("DONE");
        assertThat(jdbcTemplate.queryForObject("select finished_at from content_batch where id = ?",
                Timestamp.class, batchId)).isNotNull();
        assertThat(items()).allSatisfy(item -> assertThat(item.getDoneAt()).isNotNull());
    }

    // ---- fixture ----

    /** Shots complete (G1): all eight required STANDARD photos accepted. */
    private long readyProduct(String sku) {
        long productId = product(sku);
        for (String code : List.of("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8")) {
            media(productId, code, "ACCEPTED");
        }
        return productId;
    }

    /** Only the fact sources; the fact draft has to be queued by the tick. */
    private long productWithFactSources(String sku) {
        long productId = product(sku);
        media(productId, "P5", "ACCEPTED");
        return productId;
    }

    /** Everything a staging publish needs: locked facts, complete policy, live assets. */
    private long publishableProduct(String sku) {
        return publishableProduct(sku, null);
    }

    private long publishableProduct(String sku, Integer longCopyPolicyOverride) {
        // readyProduct already brings the accepted P5 that a fact draft needs
        long productId = readyProduct(sku);
        lockFacts(productId);
        long copyPolicy = longCopyPolicyOverride == null ? policyVersion()
                : longCopyPolicyOverride;
        asset(productId, "PAGE_MAIN", "main", "APPROVED", null);
        for (String angle : List.of("P2", "P3", "P4")) {
            asset(productId, "PAGE_ANGLE", angle, "APPROVED", null);
        }
        asset(productId, "COPY_TITLE", "default", "APPROVED", null);
        asset(productId, "COPY_SHORT", "default", "APPROVED", null);
        asset(productId, "COPY_LONG", "default", "APPROVED",
                "{\"policyVersion\":" + copyPolicy + "}");
        return productId;
    }

    /** The store policy version, saving the complete policy once per test. */
    private int policyVersion() {
        return policyService.current(tenantId).filter(view -> view.complete())
                .map(view -> view.version())
                .orElseGet(() -> policyService.save(user, completeSections()).version());
    }

    private void lockFacts(long productId) {
        jdbcTemplate.update("insert into product_fact_sheet (tenant_id, product_id, version, "
                + "facts_json, field_sources, source_refs, status) "
                + "values (?, ?, 1, '{\"sku\":\"MG-X\"}'::jsonb, '{}'::jsonb, '[]'::jsonb, 'LOCKED')",
                tenantId, productId);
    }

    private Map<PolicySection, SectionText> completeSections() {
        Map<PolicySection, SectionText> sections = new EnumMap<>(PolicySection.class);
        sections.put(PolicySection.DELIVERY, new SectionText("Delivery", "1-3 days", "Fast"));
        sections.put(PolicySection.COD, new SectionText("COD", "Pay on receipt", "COD"));
        sections.put(PolicySection.MOMO, new SectionText("MoMo", "MTN MoMo", "MoMo"));
        sections.put(PolicySection.WARRANTY, new SectionText("Warranty", "1 year", null));
        sections.put(PolicySection.RETURNS, new SectionText("Returns", "7 days", null));
        return sections;
    }

    private void asset(long productId, String specCode, String variant, String status,
            String contentJson) {
        jdbcTemplate.update(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json, content_json) "
                        + "values (?, ?, ?, ?, 1, 'TEXT', ?, '{}'::jsonb, '{}'::jsonb, "
                        + "?::jsonb)",
                tenantId, productId, specCode, variant, status, contentJson);
    }

    private void activateWorkflow(String code) {
        jdbcTemplate.update(
                "insert into comfy_workflow (tenant_id, code, version, workflow_json, manifest_json, "
                        + "model_refs, status) values (?, ?, 1, '{}', ?, '[]', 'APPROVED')",
                tenantId, code, "{\"code\":\"" + code + "\"}");
    }

    private long product(String sku) {
        return jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, "
                        + "synced_at) values (?, ?, ?, 'simple', ?, ?, 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), sku, "Product " + sku);
    }

    private long media(long productId, String shotCode, String status) {
        return jdbcTemplate.queryForObject(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, thumb_object_key, content_type, size_bytes, sha256, qc_json, status) "
                        + "values (?, ?, ?, 'PHOTO', ?, ?, null, 'image/jpeg', 1024, ?, '{}', ?) returning id",
                Long.class, tenantId, productId, shotCode, "MG-X_" + shotCode + ".jpg",
                "t" + tenantId + "/source-media/" + productId + "/" + shotCode + ".png",
                shotCode.hashCode() + "-" + productId + "0".repeat(40), status);
    }

    private List<BatchItemEntity> items() {
        return jdbcTemplate.query("select * from content_batch_item order by position",
                (rs, rowNum) -> {
                    BatchItemEntity item = new BatchItemEntity();
                    item.setId(rs.getLong("id"));
                    item.setTenantId(rs.getLong("tenant_id"));
                    item.setBatchId(rs.getLong("batch_id"));
                    item.setProductId(rs.getLong("product_id"));
                    item.setPosition(rs.getInt("position"));
                    long runId = rs.getLong("image_run_id");
                    item.setImageRunId(rs.wasNull() ? null : runId);
                    long stagingId = rs.getLong("staging_publication_id");
                    item.setStagingPublicationId(rs.wasNull() ? null : stagingId);
                    item.setBlockedReason(rs.getString("blocked_reason"));
                    item.setDoneAt(rs.getObject("done_at") == null ? null
                            : rs.getObject("done_at", java.time.OffsetDateTime.class));
                    return item;
                });
    }
}