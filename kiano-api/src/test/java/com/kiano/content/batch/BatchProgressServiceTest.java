package com.kiano.content.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.content.policy.PolicySection;
import com.kiano.content.policy.PolicyService;
import com.kiano.content.policy.PolicyService.SectionText;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.workerprotocol.JobStep;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
 * BatchProgressService (C6 Task 4): the dashboard counts the derived stages and
 * lists blocked SKUs with their reason, the ETA falls back to the configured
 * per-step GPU seconds when there is no history, and the LLM cost only counts
 * the calls of the batch's SKUs made after the batch was created.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BatchProgressServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BatchProgressService progressService;

    @Autowired
    private BatchService batches;

    @Autowired
    private BatchProperties properties;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private GenerationRunStore runs;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;
    private long storeId;
    private long userId;
    private CurrentUser user;

    @BeforeEach
    void seed() {
        for (String table : List.of("content_batch_item", "generation_job", "generation_run",
                "content_batch", "platform_task", "publication", "asset_review", "asset",
                "source_media", "product_fact_sheet", "llm_call", "product_profile",
                "product_category", "product", "category", "store_policy", "store", "app_user")) {
            jdbcTemplate.update("delete from " + table);
        }
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'batch-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        user = new CurrentUser(userId, tenantId, Role.OPERATOR, "batch-op@example.test");
    }

    /** Batch rows reference products, runs and calls: never leave them behind. */
    @AfterEach
    void cleanUp() {
        for (String table : List.of("content_batch_item", "generation_job", "generation_run",
                "content_batch", "platform_task", "publication", "asset_review", "asset",
                "source_media", "product_fact_sheet", "llm_call")) {
            jdbcTemplate.update("delete from " + table);
        }
    }

    @Test
    void eta_noHistory_usesConfiguredDefaults() {
        long productId = readyProduct("MG-STD1");
        long batchId = batches.create(user, "Spring", List.of(productId), false).id();

        BatchProgressService.BatchProgress progress = progressService.progress(tenantId, batchId);

        // the pipeline has not started: one white main page, four angles
        // (P2/P3/P4/P6), the accessory page and two scene pages are planned
        Map<String, Double> defaults = properties.getDefaultGpuSeconds();
        double expected = defaults.get("WHITE_MAIN") + 4 * defaults.get("WHITE_ANGLE")
                + defaults.get("INBOX") + 2 * defaults.get("SCENE");
        assertThat(expected).isPositive();
        assertThat(progress.eta().gpuSeconds()).isEqualTo(expected);
        assertThat(progress.eta().llmSeconds()).isZero();
        assertThat(progress.items()).singleElement()
                .satisfies(row -> assertThat(row.imageLane()).isEqualTo(BatchStage.GENERATING));
    }

    @Test
    void progress_countsStagesAndListsBlockedWithReasons() {
        long staged = publishableProduct("MG-STD1");
        long hero = product("MG-HERO1");
        long batchId = batches.create(user, "Spring", List.of(staged, hero), false).id();
        // the product turns HERO after the batch was created: the item blocks
        jdbcTemplate.update("insert into product_profile (product_id, tenant_id, content_tier) "
                + "values (?, ?, 'HERO')", hero, tenantId);

        BatchProgressService.BatchProgress progress = progressService.progress(tenantId, batchId);

        assertThat(progress.stageCounts()).containsEntry(BatchStage.STAGING, 1)
                .containsEntry(BatchStage.BLOCKED, 1);
        assertThat(progress.batch().itemCount()).isEqualTo(2);
        Map<Long, BatchProgressService.ItemRow> rows = progress.items().stream()
                .collect(Collectors.toMap(BatchProgressService.ItemRow::productId,
                        Function.identity()));
        BatchProgressService.ItemRow ready = rows.get(staged);
        assertThat(ready.sku()).isEqualTo("MG-STD1");
        assertThat(ready.stage()).isEqualTo(BatchStage.STAGING);
        assertThat(ready.imageLane()).isEqualTo(BatchStage.GENERATING);
        assertThat(ready.factLane()).isEqualTo(BatchStage.GENERATING);
        assertThat(ready.blockedReason()).isNull();

        BatchProgressService.ItemRow blocked = rows.get(hero);
        assertThat(blocked.stage()).isEqualTo(BatchStage.BLOCKED);
        assertThat(blocked.blockedReason()).isEqualTo("NOW_HERO");
        assertThat(blocked.blockedDetails()).isEmpty();
        assertThat(progress.reviewTime().skuCount()).isZero();
    }

    @Test
    void progress_costOnlyCountsBatchProductsSinceCreation() {
        long inBatch = readyProduct("MG-STD1");
        long outside = product("MG-STD2");
        // a call of the batch SKU from before the batch: not counted
        long oldCall = llmCall(0.7, "now() - interval '1 hour'");
        factSheet(inBatch, 1, "LOCKED", oldCall);
        // another product's call: not counted either
        factSheet(outside, 1, "DRAFT", llmCall(1.1, "now()"));

        long batchId = batches.create(user, "Spring", List.of(inBatch), false).id();

        long batchCall = llmCall(0.5, "now() + interval '1 minute'");
        factSheet(inBatch, 2, "DRAFT", batchCall);
        long copyCall = llmCall(0.2, "now() + interval '1 minute'");
        assetWithProvenance(inBatch, copyCall);
        long runId = runs.create(tenantId, inBatch, null);
        long jobId = jobs.create(tenantId, inBatch, runId, JobStep.CUTOUT, "P1", Map.of(),
                List.of(), GenerationJobStore.JobPriority.batch(batchId, false));
        jdbcTemplate.update(
                "update generation_job set status = 'SUCCEEDED', gpu_seconds = 12.5 where id = ?",
                jobId);

        BatchProgressService.BatchProgress progress = progressService.progress(tenantId, batchId);

        // 0.5 (fact draft) + 0.2 (copy) — 0.7 predates the batch, 1.1 is another SKU
        assertThat(progress.llmCostUsd()).isEqualByComparingTo("0.7");
        assertThat(progress.gpuSecondsUsed()).isEqualTo(12.5);
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

    /** Everything a staging publish needs: locked facts, complete policy, live assets. */
    private long publishableProduct(String sku) {
        long productId = readyProduct(sku);
        jdbcTemplate.update("insert into product_fact_sheet (tenant_id, product_id, version, "
                + "facts_json, field_sources, source_refs, status) "
                + "values (?, ?, 1, '{}'::jsonb, '{}'::jsonb, '[]'::jsonb, 'LOCKED')",
                tenantId, productId);
        int copyPolicy = policyVersion();
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
                        + "values (?, ?, ?, ?, 1, 'TEXT', ?, '{}'::jsonb, '{}'::jsonb, ?::jsonb)",
                tenantId, productId, specCode, variant, status, contentJson);
    }

    private void assetWithProvenance(long productId, long llmCallId) {
        jdbcTemplate.update(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json) "
                        + "values (?, ?, 'COPY_SHORT', 'default', 1, 'TEXT', 'IN_REVIEW', "
                        + "'{}'::jsonb, ?::jsonb)",
                tenantId, productId, "{\"llmCallId\":" + llmCallId + "}");
    }

    /**
     * The created_at is explicit: the batch's created_at is set by the JVM and
     * the call's by the database, so offsets keep the fixture independent of
     * the small clock skew between them.
     */
    private long llmCall(double costUsd, String createdAt) {
        return jdbcTemplate.queryForObject(
                "insert into llm_call (tenant_id, purpose, model, cost_usd, status, created_at) "
                        + "values (?, 'FACT_DRAFT', 'claude', ?, 'OK', " + createdAt + ") returning id",
                Long.class, tenantId, costUsd);
    }

    private void factSheet(long productId, int version, String status, long llmCallId) {
        jdbcTemplate.update("insert into product_fact_sheet (tenant_id, product_id, version, "
                + "facts_json, field_sources, source_refs, status, llm_call_id) "
                + "values (?, ?, ?, '{}'::jsonb, '{}'::jsonb, '[]'::jsonb, ?, ?)",
                tenantId, productId, version, status, llmCallId);
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
}