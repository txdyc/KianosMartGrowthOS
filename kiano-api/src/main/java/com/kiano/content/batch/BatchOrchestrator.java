package com.kiano.content.batch;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.content.batch.BatchStageResolver.ItemSnapshot;
import com.kiano.content.batch.BatchStageResolver.Resolution;
import com.kiano.content.facts.FactDraftTaskHandler;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.content.pipeline.PipelineService;
import com.kiano.content.publish.PublicationService;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.queue.TaskQueue;
import com.kiano.platform.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives every RUNNING batch (C6 Task 3): each tick derives the stage of every
 * item and performs the next automatic step - start the image pipeline, queue
 * the fact draft, publish to staging as SYSTEM, finish the batch. Every action
 * is idempotent and the whole tenant round runs under a PostgreSQL advisory
 * lock, so overlapping ticks or an api restart never start or publish twice
 * (Review Focus 4). Batch GPU work keeps its priority and night flag
 * (Review Focus 1); a single failing item never stops the batch (Review Focus 3).
 */
@Component
@Profile("!worker")
public class BatchOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BatchOrchestrator.class);

    private final BatchProperties properties;
    private final BatchMapper batchMapper;
    private final BatchItemMapper itemMapper;
    private final BatchSnapshotReader snapshotReader;
    private final PipelineService pipeline;
    private final GenerationRunStore runs;
    private final TaskQueue queue;
    private final PublicationService publications;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public BatchOrchestrator(BatchProperties properties, BatchMapper batchMapper,
            BatchItemMapper itemMapper, BatchSnapshotReader snapshotReader, PipelineService pipeline,
            GenerationRunStore runs, TaskQueue queue, PublicationService publications,
            AuditLog auditLog, ObjectMapper objectMapper, JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        this.properties = properties;
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.snapshotReader = snapshotReader;
        this.pipeline = pipeline;
        this.runs = runs;
        this.queue = queue;
        this.publications = publications;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${kiano.batch.tick:30s}",
            initialDelayString = "${kiano.batch.tick:30s}")
    public void scheduled() {
        if (properties.isEnabled()) {
            tickOnce();
        }
    }

    /** One round over every tenant that has a running batch. */
    public void tickOnce() {
        for (long tenantId : runningBatchTenants()) {
            try {
                transactionTemplate.executeWithoutResult(status -> tickTenant(tenantId));
            } catch (RuntimeException ex) {
                log.warn("Batch tick for tenant {} failed: {}", tenantId, ex.getMessage());
            }
        }
    }

    /**
     * One tenant's round; the advisory lock lives in this transaction, so a
     * second instance (or an overlapping tick) skips the tenant instead of
     * duplicating the work.
     */
    public void tickTenant(long tenantId) {
        Boolean locked = jdbcTemplate.queryForObject("select pg_try_advisory_xact_lock(hashtext(?))",
                Boolean.class, "kiano-batch:" + tenantId);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        for (BatchEntity batch : batchMapper.selectList(Wrappers.<BatchEntity>lambdaQuery()
                .eq(BatchEntity::getTenantId, tenantId)
                .eq(BatchEntity::getStatus, "RUNNING")
                .orderByAsc(BatchEntity::getId))) {
            tickBatch(tenantId, batch);
        }
    }

    private void tickBatch(long tenantId, BatchEntity batch) {
        List<BatchItemEntity> items = itemMapper.selectList(Wrappers.<BatchItemEntity>lambdaQuery()
                .eq(BatchItemEntity::getTenantId, tenantId)
                .eq(BatchItemEntity::getBatchId, batch.getId())
                .orderByAsc(BatchItemEntity::getPosition));
        if (items.isEmpty()) {
            return;
        }
        Map<Long, ItemSnapshot> snapshots = snapshotReader.read(tenantId, items);
        Round round = new Round();
        boolean allDone = true;
        for (BatchItemEntity item : items) {
            ItemSnapshot snapshot = snapshots.get(item.getProductId());
            try {
                if (snapshot == null) {
                    recordBlocked(tenantId, batch, item, "PRODUCT_GONE", Map.of());
                    allDone = false;
                    continue;
                }
                tickItem(tenantId, batch, item, snapshot, round);
                Resolution resolution = BatchStageResolver.resolve(snapshot);
                if (resolution.stage() != BatchStage.DONE) {
                    allDone = false;
                } else if (item.getDoneAt() == null) {
                    item.setDoneAt(OffsetDateTime.now(ZoneOffset.UTC));
                    itemMapper.updateById(item);
                }
            } catch (RuntimeException ex) {
                // one broken item must not stop the other SKUs (Review Focus 3)
                log.warn("Batch {} item {} failed: {}", batch.getId(), item.getProductId(),
                        ex.getMessage());
                recordBlocked(tenantId, batch, item, "INTERNAL",
                        Map.of("error", String.valueOf(ex.getMessage())));
                allDone = false;
            }
        }
        if (allDone) {
            batch.setStatus("DONE");
            batch.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            batchMapper.updateById(batch);
        }
    }

    /** The next automatic step of one item; every step is idempotent. */
    private void tickItem(long tenantId, BatchEntity batch, BatchItemEntity item,
            ItemSnapshot snapshot, Round round) {
        Resolution resolution = BatchStageResolver.resolve(snapshot);
        if (resolution.stage() == BatchStage.BLOCKED) {
            recordBlocked(tenantId, batch, item, resolution.blockedReason(), resolution.details());
            return;
        }
        if (item.getBlockedReason() != null) {
            item.setBlockedReason(null);
            item.setBlockedDetails(null);
            itemMapper.updateById(item);
        }
        if (item.getImageRunId() != null && "RUNNING".equals(snapshot.imageRunStatus())) {
            round.activePipelines++;
        }
        // 1. start the image pipeline (max-active-pipelines at a time)
        if (snapshot.shotsComplete() && item.getImageRunId() == null
                && resolution.imageLane() != BatchStage.IMAGES) {
            if (round.activePipelines >= properties.getMaxActivePipelines()) {
                return; // wait for a slot; the next tick picks this item up again
            }
            long runId = startPipeline(tenantId, batch, item);
            item.setImageRunId(runId);
            itemMapper.updateById(item);
            round.activePipelines++;
        }
        // 2. queue the fact draft (capped per round)
        if (snapshot.hasFactSources() && snapshot.factStatus() == null
                && !snapshot.factDraftRunning()
                && round.queuedDrafts < properties.getMaxQueuedFactDrafts()) {
            queueFactDraft(tenantId, batch, item, round);
        }
        // 3. publish the finished SKU to staging, spaced by publish-interval
        if (resolution.stage() == BatchStage.STAGING && item.getStagingPublicationId() == null) {
            requestStaging(tenantId, batch, item);
        }
    }

    private long startPipeline(long tenantId, BatchEntity batch, BatchItemEntity item) {
        boolean nightOnly = Boolean.TRUE.equals(batch.getNightOnly());
        try {
            long runId = pipeline.startForBatch(tenantId, item.getProductId(), batch.getId(),
                    nightOnly);
            audit(tenantId, "BATCH_PIPELINE_STARTED", batch,
                    Map.of("productId", item.getProductId(), "runId", runId,
                            "nightOnly", nightOnly));
            return runId;
        } catch (ApiException ex) {
            if (!"PIPELINE_RUNNING".equals(ex.getCode())) {
                throw ex;
            }
            // someone started a pipeline outside the batch: adopt that run
            long runId = runs.latest(tenantId, item.getProductId())
                    .orElseThrow(() -> ex).id();
            audit(tenantId, "BATCH_PIPELINE_STARTED", batch,
                    Map.of("productId", item.getProductId(), "runId", runId, "adopted", true));
            return runId;
        }
    }

    private void queueFactDraft(long tenantId, BatchEntity batch, BatchItemEntity item,
            Round round) {
        Optional<Long> taskId = queue.enqueue(tenantId, FactDraftTaskHandler.TYPE,
                Map.of("productId", item.getProductId()), "fact-draft:" + item.getProductId(),
                10, null);
        if (taskId.isPresent()) {
            round.queuedDrafts++;
            audit(tenantId, "BATCH_FACT_DRAFT_QUEUED", batch,
                    Map.of("productId", item.getProductId()));
        }
    }

    private void requestStaging(long tenantId, BatchEntity batch, BatchItemEntity item) {
        Instant runAfter = nextPublishSlot(tenantId);
        long publicationId = publications.requestSystem(tenantId, item.getProductId(),
                batch.getId(), runAfter);
        item.setStagingPublicationId(publicationId);
        itemMapper.updateById(item);
        audit(tenantId, "BATCH_STAGING_REQUESTED", batch,
                Map.of("productId", item.getProductId(), "publicationId", publicationId,
                        "runAfter", runAfter.toString()));
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

    /** The blocked reason is stored and audited once, not on every tick. */
    private void recordBlocked(long tenantId, BatchEntity batch, BatchItemEntity item,
            String reason, Map<String, Object> details) {
        if (reason != null && reason.equals(item.getBlockedReason())) {
            return;
        }
        item.setBlockedReason(reason);
        item.setBlockedDetails(objectMapper.writeValueAsString(details));
        itemMapper.updateById(item);
        audit(tenantId, "BATCH_ITEM_BLOCKED", batch,
                Map.of("productId", item.getProductId(), "reason", String.valueOf(reason),
                        "details", details));
    }

    private void audit(long tenantId, String action, BatchEntity batch,
            Map<String, Object> after) {
        auditLog.record(new AuditEntry(tenantId, ActorType.SYSTEM, "SYSTEM", action, "batch",
                String.valueOf(batch.getId()), null, after, null, "BATCH"));
    }

    private List<Long> runningBatchTenants() {
        return jdbcTemplate.queryForList(
                "select distinct tenant_id from content_batch where status = 'RUNNING'",
                Long.class);
    }

    /** Per-round counters: the concurrency caps apply to one batch. */
    private static final class Round {
        private int activePipelines;
        private int queuedDrafts;
    }
}