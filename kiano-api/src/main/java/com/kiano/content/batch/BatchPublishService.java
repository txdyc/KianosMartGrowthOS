package com.kiano.content.batch;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.content.batch.BatchStageResolver.ItemSnapshot;
import com.kiano.content.publish.PublicationService;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OWNER bulk production publish of a batch (C6 Task 6): only SKUs whose stage
 * is READY_FOR_PRODUCTION are queued, the staging gate still applies per SKU
 * (it becomes a STAGING_REQUIRED rejection), and the publishes are spaced by
 * the batch publish interval so Woo is never hit in one burst. The staging
 * retry of the dashboard clears a FAILED reference; the next tick republishes.
 */
@Service
public class BatchPublishService {

    /** What the OWNER action queued and what it rejected, with reasons. */
    public record ProductionResult(List<Long> queued, Map<Long, String> rejected) {
    }

    private final BatchService batchService;
    private final BatchItemMapper itemMapper;
    private final BatchSnapshotReader snapshotReader;
    private final BatchProperties properties;
    private final PublicationService publications;
    private final AuditLog auditLog;
    private final JdbcTemplate jdbcTemplate;

    public BatchPublishService(BatchService batchService, BatchItemMapper itemMapper,
            BatchSnapshotReader snapshotReader, BatchProperties properties,
            PublicationService publications, AuditLog auditLog, JdbcTemplate jdbcTemplate) {
        this.batchService = batchService;
        this.itemMapper = itemMapper;
        this.snapshotReader = snapshotReader;
        this.properties = properties;
        this.publications = publications;
        this.auditLog = auditLog;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Queues the selected READY SKUs; each publish is its own transaction, so
     * one rejection (for example a changed staging set) never rolls back the
     * SKUs already queued.
     */
    public ProductionResult publishProduction(CurrentUser user, long batchId,
            List<Long> productIds) {
        if (user.role() != Role.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Publishing to production requires the OWNER role");
        }
        if (batchService.find(user.tenantId(), batchId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Batch not found");
        }
        List<BatchItemEntity> items = batchService.items(user.tenantId(), batchId);
        Map<Long, ItemSnapshot> snapshots = snapshotReader.read(user.tenantId(), items);
        Map<Long, BatchItemEntity> itemByProduct = new LinkedHashMap<>();
        for (BatchItemEntity item : items) {
            itemByProduct.put(item.getProductId(), item);
        }
        List<Long> queued = new ArrayList<>();
        Map<Long, String> rejected = new LinkedHashMap<>();
        for (long productId : productIds == null ? List.<Long>of() : productIds) {
            BatchItemEntity item = itemByProduct.get(productId);
            ItemSnapshot snapshot = snapshots.get(productId);
            if (item == null || snapshot == null) {
                rejected.put(productId, "NOT_IN_BATCH");
                continue;
            }
            BatchStageResolver.Resolution resolution = BatchStageResolver.resolve(snapshot);
            // a FAILED production publish is retried the same way (staging still holds)
            boolean ready = resolution.stage() == BatchStage.READY_FOR_PRODUCTION
                    || "PRODUCTION_FAILED".equals(resolution.blockedReason());
            if (!ready) {
                rejected.put(productId, "NOT_READY");
                continue;
            }
            try {
                long publicationId = publications.requestProductionSpaced(user, productId,
                        nextPublishSlot(user.tenantId()));
                item.setProductionPublicationId(publicationId);
                itemMapper.updateById(item);
                queued.add(productId);
            } catch (ApiException ex) {
                // e.g. STAGING_REQUIRED, COPY_POLICY_OUTDATED: reported per SKU,
                // the SKUs already queued stay queued
                rejected.put(productId, ex.getCode());
            }
        }
        if (!queued.isEmpty()) {
            auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                    String.valueOf(user.userId()), "BATCH_PRODUCTION_REQUESTED", "batch",
                    String.valueOf(batchId), null, Map.of("productIds", queued), null, "BATCH"));
        }
        return new ProductionResult(queued, rejected);
    }

    /**
     * The dashboard's retry of a FAILED staging publish: the reference is
     * cleared and the next orchestrator tick requests the publish again.
     */
    @Transactional
    public void retryStaging(CurrentUser user, long batchId, long productId) {
        BatchItemEntity item = batchService.items(user.tenantId(), batchId).stream()
                .filter(row -> row.getProductId() == productId).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Batch item not found"));
        if (item.getStagingPublicationId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "BATCH_STATE",
                    "This item has no staging publication to retry");
        }
        List<String> statuses = jdbcTemplate.queryForList("select status from publication "
                + "where tenant_id = ? and id = ?", String.class, user.tenantId(),
                item.getStagingPublicationId());
        if (statuses.isEmpty() || !"FAILED".equals(statuses.get(0))) {
            throw new ApiException(HttpStatus.CONFLICT, "BATCH_STATE",
                    "Only a FAILED staging publish can be retried");
        }
        // updateById would keep the old values: clearing needs an explicit set
        itemMapper.update(null, Wrappers.<BatchItemEntity>lambdaUpdate()
                .eq(BatchItemEntity::getId, item.getId())
                .set(BatchItemEntity::getStagingPublicationId, null)
                .set(BatchItemEntity::getBlockedReason, null)
                .set(BatchItemEntity::getBlockedDetails, null));
    }

    /** Earliest slot: the tenant's last batch publish plus the publish interval. */
    private Instant nextPublishSlot(long tenantId) {
        Timestamp last = jdbcTemplate.queryForObject(
                "select max(run_after) from platform_task where tenant_id = ? "
                        + "and type = 'PUBLISH_PRODUCT' and priority = 10",
                Timestamp.class, tenantId);
        Instant now = Instant.now();
        if (last == null) {
            return now;
        }
        Instant earliest = last.toInstant().plus(properties.getPublishInterval());
        return earliest.isAfter(now) ? earliest : now;
    }
}