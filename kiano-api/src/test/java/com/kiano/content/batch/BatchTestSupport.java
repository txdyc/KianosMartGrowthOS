package com.kiano.content.batch;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.content.policy.PolicySection;
import com.kiano.content.policy.PolicyService;
import com.kiano.content.policy.PolicyService.SectionText;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Shared fixture of the C6 batch test classes (Task 6): products with complete
 * shots, publishable SKUs (locked facts, complete policy, approved assets),
 * publications and the per-test cleanup. The database is shared across test
 * classes, so every fixture table is cleared before and after each test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
abstract class BatchTestSupport {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected BatchService batches;

    @Autowired
    protected BatchProperties properties;

    @Autowired
    protected PolicyService policyService;

    @Autowired
    protected GenerationJobStore jobs;

    @Autowired
    protected GenerationRunStore runs;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    protected long tenantId;
    protected long storeId;
    protected long userId;
    protected CurrentUser operator;
    protected CurrentUser owner;

    @BeforeEach
    void seedBase() {
        clearTables();
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update("delete from audit_log where tenant_id = ?", tenantId);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        userId = user("batch-op@example.test", "OPERATOR");
        long ownerId = user("batch-owner@example.test", "OWNER");
        operator = new CurrentUser(userId, tenantId, Role.OPERATOR, "batch-op@example.test");
        owner = new CurrentUser(ownerId, tenantId, Role.OWNER, "batch-owner@example.test");
        activateWorkflow("CUTOUT");
        activateWorkflow("SCENE");
    }

    /** Batch rows reference products, runs and calls: never leave them behind. */
    @AfterEach
    void cleanUpBase() {
        clearTables();
    }

    private void clearTables() {
        for (String table : List.of("content_batch_item", "generation_job", "generation_run",
                "content_batch", "platform_task", "publication", "asset_review", "asset",
                "source_media", "product_fact_sheet", "llm_call", "review_session",
                "comfy_workflow", "product_profile", "product_category", "product", "category",
                "store_policy", "store", "app_user")) {
            jdbcTemplate.update("delete from " + table);
        }
    }

    // ---- fixture ----

    /** Shots complete (G1): all eight required STANDARD photos accepted. */
    protected long readyProduct(String sku) {
        long productId = product(sku);
        for (String code : List.of("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8")) {
            media(productId, code, "ACCEPTED");
        }
        return productId;
    }

    /** Everything a staging publish needs: locked facts, complete policy, live assets. */
    protected long publishableProduct(String sku) {
        long productId = readyProduct(sku);
        lockFacts(productId);
        int copyPolicy = policyVersion();
        asset(productId, "PAGE_MAIN", "main", "APPROVED", null, "{}");
        for (String angle : List.of("P2", "P3", "P4")) {
            asset(productId, "PAGE_ANGLE", angle, "APPROVED", null, "{}");
        }
        asset(productId, "COPY_TITLE", "default", "APPROVED", null, "{}");
        asset(productId, "COPY_SHORT", "default", "APPROVED", null, "{}");
        asset(productId, "COPY_LONG", "default", "APPROVED",
                "{\"policyVersion\":" + copyPolicy + "}", "{}");
        return productId;
    }

    /** The store policy version, saving the complete policy once per test. */
    protected int policyVersion() {
        return policyService.current(tenantId).filter(view -> view.complete())
                .map(view -> view.version())
                .orElseGet(() -> policyService.save(operator, completeSections()).version());
    }

    protected void lockFacts(long productId) {
        jdbcTemplate.update("insert into product_fact_sheet (tenant_id, product_id, version, "
                + "facts_json, field_sources, source_refs, status) "
                + "values (?, ?, 1, '{\"model\":\"Kiano\"}'::jsonb, '{}'::jsonb, '[]'::jsonb, "
                + "'LOCKED')", tenantId, productId);
    }

    /** A DRAFT fact sheet with per-field sources, waiting for the human lock. */
    protected void draftFacts(long productId, String fieldSourcesJson) {
        jdbcTemplate.update("insert into product_fact_sheet (tenant_id, product_id, version, "
                + "facts_json, field_sources, source_refs, status) "
                + "values (?, ?, 1, '{\"model\":\"Kiano\"}'::jsonb, ?::jsonb, '[]'::jsonb, 'DRAFT')",
                tenantId, productId, fieldSourcesJson);
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

    protected long asset(long productId, String specCode, String variant, String status,
            @Nullable String contentJson, String precheckJson) {
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json, content_json) "
                        + "values (?, ?, ?, ?, 1, 'TEXT', ?, ?::jsonb, '{}'::jsonb, ?::jsonb) "
                        + "returning id",
                Long.class, tenantId, productId, specCode, variant, status, precheckJson,
                contentJson);
    }

    /** The asset ids a publish of this SKU records (gallery + copy, insertion order). */
    protected List<Long> approvedAssetIds(long productId) {
        return jdbcTemplate.queryForList("select id from asset where tenant_id = ? "
                + "and product_id = ? and status = 'APPROVED' order by id", Long.class, tenantId,
                productId);
    }

    /** One WOO_PRODUCT publication row with the given asset set and status. */
    protected long publication(long productId, String environment, String status,
            List<Long> assetIds, @Nullable String error) {
        return jdbcTemplate.queryForObject(
                "insert into publication (tenant_id, product_id, environment, target, asset_ids, "
                        + "uploaded_media_ids, archived_asset_ids, status, error) "
                        + "values (?, ?, ?, 'WOO_PRODUCT', ?::jsonb, '[]'::jsonb, '[]'::jsonb, ?, ?) "
                        + "returning id",
                Long.class, tenantId, productId, environment, assetIds.toString(), status, error);
    }

    protected void activateWorkflow(String code) {
        jdbcTemplate.update(
                "insert into comfy_workflow (tenant_id, code, version, workflow_json, manifest_json, "
                        + "model_refs, status) values (?, ?, 1, '{}', ?, '[]', 'APPROVED')",
                tenantId, code, "{\"code\":\"" + code + "\"}");
    }

    protected long product(String sku) {
        return jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, "
                        + "synced_at) values (?, ?, ?, 'simple', ?, ?, 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), sku, "Product " + sku);
    }

    protected long user(String email, String role) {
        return jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, ?, 'Op', ?, ?) returning id",
                Long.class, tenantId, email, passwordEncoder.encode("op-pass-123"), role);
    }

    protected long media(long productId, String shotCode, String status) {
        return jdbcTemplate.queryForObject(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, thumb_object_key, content_type, size_bytes, sha256, qc_json, status) "
                        + "values (?, ?, ?, 'PHOTO', ?, ?, ?, 'image/jpeg', 1024, ?, '{}', ?) returning id",
                Long.class, tenantId, productId, shotCode, "MG-X_" + shotCode + ".jpg",
                "t" + tenantId + "/source-media/" + productId + "/" + shotCode + ".png",
                "t" + tenantId + "/source-media/" + productId + "/" + shotCode + "_thumb.png",
                shotCode.hashCode() + "-" + productId + shotCode.hashCode() + "0".repeat(30), status);
    }

    /** A PROMO (marketing image) source of the product. */
    protected long promo(long productId, String status) {
        return jdbcTemplate.queryForObject(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, thumb_object_key, content_type, size_bytes, sha256, qc_json, status) "
                        + "values (?, ?, 'PROMO', 'PROMO_IMAGE', 'promo.jpg', ?, null, 'image/jpeg', "
                        + "1024, ?, '{}', ?) returning id",
                Long.class, tenantId, productId,
                "t" + tenantId + "/source-media/" + productId + "/promo.png",
                "promo-" + productId + "0".repeat(40), status);
    }

    protected List<BatchItemEntity> items() {
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
                    long productionId = rs.getLong("production_publication_id");
                    item.setProductionPublicationId(rs.wasNull() ? null : productionId);
                    item.setBlockedReason(rs.getString("blocked_reason"));
                    return item;
                });
    }
}