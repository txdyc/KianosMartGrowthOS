package com.kiano.content.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.content.batch.BatchPublishService.ProductionResult;
import com.kiano.platform.web.ApiException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * BatchPublishService (C6 Task 6): only the OWNER publishes production, only
 * READY_FOR_PRODUCTION SKUs are queued, the staging gate still applies per SKU
 * (STAGING_REQUIRED), the tasks are spaced by the publish interval, and the
 * staging retry of a FAILED publish is picked up by the next tick.
 */
class BatchPublishServiceTest extends BatchTestSupport {

    @Autowired
    private BatchPublishService publishService;

    @Autowired
    private BatchOrchestrator orchestrator;

    @Test
    void publishProduction_ownerOnly_notReadyRejected_spacedRunAfter() {
        properties.setPublishInterval(Duration.ofSeconds(5));
        long first = publishableProduct("MG-STD1");
        long second = publishableProduct("MG-STD2");
        long notReady = readyProduct("MG-STD3");
        for (long product : List.of(first, second)) {
            publication(product, "STAGING", "APPLIED", approvedAssetIds(product), null);
        }
        long batchId = batches.create(operator, "Spring", List.of(first, second, notReady), false)
                .id();

        assertThatThrownBy(() -> publishService.publishProduction(operator, batchId, List.of(first)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(403);
                    assertThat(ex.getCode()).isEqualTo("FORBIDDEN");
                });

        ProductionResult result = publishService.publishProduction(owner, batchId,
                List.of(first, second, notReady, 999999L));

        assertThat(result.queued()).containsExactly(first, second);
        assertThat(result.rejected()).containsEntry(notReady, "NOT_READY")
                .containsEntry(999999L, "NOT_IN_BATCH");
        List<Map<String, Object>> publications = jdbcTemplate.queryForList(
                "select product_id, status, published_by from publication "
                        + "where environment = 'PRODUCTION' order by id");
        assertThat(publications).hasSize(2);
        assertThat(publications).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(((Number) row.get("published_by")).longValue()).isEqualTo(owner.userId());
        });
        // batch priority and the spacing between the two publishes
        List<Timestamp> runAfters = jdbcTemplate.queryForList(
                "select run_after from platform_task where type = 'PUBLISH_PRODUCT' "
                        + "and priority = 10 order by id", Timestamp.class);
        assertThat(runAfters).hasSize(2);
        assertThat(Duration.between(runAfters.get(0).toInstant(), runAfters.get(1).toInstant())
                .toSeconds()).isGreaterThanOrEqualTo(4);
        assertThat(items()).allSatisfy(item -> {
            if (item.getProductId() == first || item.getProductId() == second) {
                assertThat(item.getProductionPublicationId()).isNotNull();
            } else {
                assertThat(item.getProductionPublicationId()).isNull();
            }
        });
        assertThat(jdbcTemplate.queryForObject("select count(*) from audit_log where action = "
                + "'BATCH_PRODUCTION_REQUESTED' and tenant_id = ?", Integer.class, tenantId))
                .isEqualTo(1);
    }

    @Test
    void publishProduction_oneSkusPublishError_isReportedPerSku_othersStillQueued() {
        long healthy = publishableProduct("MG-STD1");
        long outdated = publishableProduct("MG-STD2");
        for (long product : List.of(healthy, outdated)) {
            publication(product, "STAGING", "APPLIED", approvedAssetIds(product), null);
        }
        // the policy changed after outdated's long copy was rendered
        jdbcTemplate.update("update asset set content_json = '{\"policyVersion\":0}'::jsonb "
                + "where product_id = ? and spec_code = 'COPY_LONG'", outdated);
        long batchId = batches.create(operator, "Spring", List.of(outdated, healthy), false).id();

        ProductionResult result = publishService.publishProduction(owner, batchId,
                List.of(outdated, healthy));

        assertThat(result.queued()).containsExactly(healthy);
        assertThat(result.rejected()).containsEntry(outdated, "COPY_POLICY_OUTDATED");
    }

    @Test
    void publishProduction_retriesAFailedProductionPublish() {
        long product = publishableProduct("MG-STD1");
        publication(product, "STAGING", "APPLIED", approvedAssetIds(product), null);
        publication(product, "PRODUCTION", "FAILED", approvedAssetIds(product),
                "WOO_TIMEOUT: read timed out");
        long batchId = batches.create(operator, "Spring", List.of(product), false).id();

        ProductionResult result = publishService.publishProduction(owner, batchId, List.of(product));

        assertThat(result.queued()).containsExactly(product);
        assertThat(jdbcTemplate.queryForObject("select count(*) from publication where "
                + "environment = 'PRODUCTION' and status = 'PENDING'", Integer.class)).isEqualTo(1);
    }

    @Test
    void publishProduction_stagingGateStillEnforced() {
        long product = publishableProduct("MG-STD1");
        // the staging publish recorded a different asset set
        publication(product, "STAGING", "APPLIED", List.of(), null);
        long batchId = batches.create(operator, "Spring", List.of(product), false).id();

        ProductionResult result = publishService.publishProduction(owner, batchId, List.of(product));

        assertThat(result.queued()).isEmpty();
        assertThat(result.rejected()).containsEntry(product, "STAGING_REQUIRED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from publication "
                + "where environment = 'PRODUCTION'", Integer.class)).isZero();
    }

    @Test
    void retryStaging_clearsFailedRef_nextTickRepublishes() {
        long product = publishableProduct("MG-STD1");
        long failedPublication = publication(product, "STAGING", "FAILED",
                approvedAssetIds(product), "PUBLISH_FAILED: boom");
        long batchId = batches.create(operator, "Spring", List.of(product), false).id();
        jdbcTemplate.update("update content_batch_item set staging_publication_id = ? "
                + "where batch_id = ? and product_id = ?", failedPublication, batchId, product);

        orchestrator.tickTenant(tenantId);

        assertThat(items().get(0).getBlockedReason()).isEqualTo("PUBLISH_FAILED");
        assertThat(jdbcTemplate.queryForObject("select count(*) from publication "
                + "where environment = 'STAGING'", Integer.class)).isEqualTo(1);

        // the dashboard's retry clears the FAILED reference...
        publishService.retryStaging(operator, batchId, product);

        assertThat(items().get(0).getStagingPublicationId()).isNull();
        assertThat(items().get(0).getBlockedReason()).isNull();

        // ...and the next tick requests the staging publish again
        orchestrator.tickTenant(tenantId);

        List<Map<String, Object>> staging = jdbcTemplate.queryForList(
                "select id, status from publication where environment = 'STAGING' order by id");
        assertThat(staging).hasSize(2);
        assertThat(staging.get(1).get("status")).isEqualTo("PENDING");
        assertThat(items().get(0).getStagingPublicationId())
                .isEqualTo(((Number) staging.get(1).get("id")).longValue());

        // retrying again while the new publish is pending is a 409
        assertThatThrownBy(() -> publishService.retryStaging(operator, batchId, product))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.getCode()).isEqualTo("BATCH_STATE");
                });
    }
}