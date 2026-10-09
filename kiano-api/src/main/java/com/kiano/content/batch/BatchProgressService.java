package com.kiano.content.batch;

import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.ContentTier;
import com.kiano.content.batch.BatchStageResolver.ItemSnapshot;
import com.kiano.content.batch.BatchStageResolver.Resolution;
import com.kiano.content.pipeline.PipelineProperties;
import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.JobStep;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The dashboard data of one batch (C6 Task 4): stage counts, one row per SKU
 * with both lanes and the blocked reason, the three ETAs (GPU spans the night
 * window for night-only batches), the LLM cost and GPU seconds spent, and the
 * per-SKU review time (Task 5). The stages come from the same
 * {@link BatchStageResolver} the orchestrator uses - never a second copy.
 */
@Service
public class BatchProgressService {

    /** One SKU row of the dashboard. */
    public record ItemRow(long productId, @Nullable String sku, @Nullable String name,
            BatchStage stage, BatchStage imageLane, BatchStage factLane,
            @Nullable String blockedReason, Map<String, Object> blockedDetails,
            @Nullable String stagingStatus, @Nullable String productionStatus) {
    }

    /** Everything the dashboard shows for one batch. */
    public record BatchProgress(BatchService.BatchView batch, Map<BatchStage, Integer> stageCounts,
            List<ItemRow> items, EtaCalculator.Eta eta, BigDecimal llmCostUsd,
            double gpuSecondsUsed, ReviewSessionService.ReviewTimeStats reviewTime) {
    }

    private final BatchService batchService;
    private final BatchSnapshotReader snapshotReader;
    private final ReviewSessionService reviewSessions;
    private final BatchProperties properties;
    private final PipelineProperties pipelineProperties;
    private final ProductCatalog productCatalog;
    private final JdbcTemplate jdbcTemplate;

    public BatchProgressService(BatchService batchService, BatchSnapshotReader snapshotReader,
            ReviewSessionService reviewSessions, BatchProperties properties,
            PipelineProperties pipelineProperties, ProductCatalog productCatalog,
            JdbcTemplate jdbcTemplate) {
        this.batchService = batchService;
        this.snapshotReader = snapshotReader;
        this.reviewSessions = reviewSessions;
        this.properties = properties;
        this.pipelineProperties = pipelineProperties;
        this.productCatalog = productCatalog;
        this.jdbcTemplate = jdbcTemplate;
    }

    public BatchProgress progress(long tenantId, long batchId) {
        BatchEntity batch = batchService.find(tenantId, batchId);
        if (batch == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Batch not found");
        }
        List<BatchItemEntity> items = batchService.items(tenantId, batchId);
        Map<Long, ItemSnapshot> snapshots = snapshotReader.read(tenantId, items);
        Map<BatchStage, Integer> stageCounts = new LinkedHashMap<>();
        List<ItemRow> rows = new ArrayList<>();
        List<ItemSnapshot> ordered = new ArrayList<>();
        List<Resolution> resolutions = new ArrayList<>();
        for (BatchItemEntity item : items) {
            ItemSnapshot snapshot = snapshots.get(item.getProductId());
            Resolution resolution = snapshot == null
                    ? new Resolution(BatchStage.BLOCKED, "PRODUCT_GONE", Map.of(),
                            BatchStage.WAITING_SHOTS, BatchStage.FACTS_DRAFTING)
                    : BatchStageResolver.resolve(snapshot);
            stageCounts.merge(resolution.stage(), 1, Integer::sum);
            ProductView product = productCatalog.findById(tenantId, item.getProductId())
                    .orElse(null);
            rows.add(new ItemRow(item.getProductId(), product == null ? null : product.sku(),
                    product == null ? null : product.name(), resolution.stage(),
                    resolution.imageLane(), resolution.factLane(), resolution.blockedReason(),
                    resolution.details(),
                    snapshot == null ? null : snapshot.stagingStatus(),
                    snapshot == null ? null : snapshot.productionStatus()));
            ordered.add(snapshot);
            resolutions.add(resolution);
        }
        return new BatchProgress(new BatchService.BatchView(batch.getId(), batch.getName(),
                batch.getStatus(), Boolean.TRUE.equals(batch.getNightOnly()), items.size(),
                batch.getCreatedAt(), batch.getFinishedAt()),
                stageCounts, rows, eta(tenantId, batch, items, ordered, resolutions),
                llmCostUsd(tenantId, batch), gpuSecondsUsed(tenantId, batchId),
                reviewSessions.stats(tenantId, batchId));
    }

    /** The three estimates of the batch's remaining work. */
    private EtaCalculator.Eta eta(long tenantId, BatchEntity batch, List<BatchItemEntity> items,
            List<ItemSnapshot> snapshots, List<Resolution> resolutions) {
        Map<JobStep, Integer> remaining = remainingJobs(tenantId, batch, items);
        int factDrafts = 0;
        int copyRuns = 0;
        int awaitingHuman = 0;
        for (int i = 0; i < items.size(); i++) {
            ItemSnapshot snapshot = snapshots.get(i);
            if (snapshot == null) {
                continue;
            }
            if (snapshot.factStatus() == null && snapshot.hasFactSources()) {
                factDrafts++;
            }
            if ("LOCKED".equals(snapshot.factStatus())
                    && resolutions.get(i).stage() == BatchStage.GENERATING) {
                copyRuns++;
            }
            BatchStage stage = resolutions.get(i).stage();
            if (stage == BatchStage.AWAITING_FACT_LOCK || stage == BatchStage.IN_REVIEW) {
                awaitingHuman++;
            }
        }
        ReviewSessionService.ReviewTimeStats review = reviewSessions.stats(tenantId,
                batch.getId());
        double avgHumanSeconds = review.skuCount() > 0 ? review.avgTotalSeconds()
                : ReviewSessionService.TARGET_SECONDS;
        return EtaCalculator.estimate(new EtaCalculator.EtaInput(remaining,
                gpuAverages(tenantId, remaining), factDrafts, copyRuns,
                llmAverageSeconds(tenantId, "FACT_DRAFT"), llmAverageSeconds(tenantId, "COPY"),
                awaitingHuman, avgHumanSeconds, Boolean.TRUE.equals(batch.getNightOnly()),
                properties.nightWindowSpec(), Instant.now()));
    }

    /**
     * GPU jobs still to run: the batch's queued/leased jobs plus the planned
     * pages of SKUs whose pipeline has not started (one white main page, one
     * page per available angle, the accessory page and the scene pages).
     */
    private Map<JobStep, Integer> remainingJobs(long tenantId, BatchEntity batch,
            List<BatchItemEntity> items) {
        Map<JobStep, Integer> remaining = new LinkedHashMap<>();
        jdbcTemplate.query("select step, count(*) as n from generation_job where tenant_id = ? "
                + "and status in ('QUEUED','LEASED','WAITING_EXECUTOR') and (batch_id = ? or run_id "
                + "in (select image_run_id from content_batch_item where batch_id = ? and "
                + "image_run_id is not null)) group by step",
                rs -> {
                    remaining.merge(JobStep.valueOf(rs.getString("step")), rs.getInt("n"),
                            Integer::sum);
                },
                tenantId, batch.getId(), batch.getId());
        List<Long> waiting = items.stream().filter(item -> item.getImageRunId() == null)
                .map(BatchItemEntity::getProductId).toList();
        if (waiting.isEmpty()) {
            return remaining;
        }
        Map<Long, Set<String>> accepted = new HashMap<>();
        Set<Long> waitingIds = new HashSet<>(waiting);
        jdbcTemplate.query("select product_id, shot_code from source_media where tenant_id = ? "
                + "and status = 'ACCEPTED'",
                rs -> {
                    long productId = rs.getLong("product_id");
                    if (waitingIds.contains(productId)) {
                        accepted.computeIfAbsent(productId, key -> new HashSet<>())
                                .add(rs.getString("shot_code"));
                    }
                },
                tenantId);
        int sceneCount = pipelineProperties.getSceneCount()
                .getOrDefault(ContentTier.STANDARD.name(), 2);
        for (long productId : waiting) {
            Set<String> shots = accepted.getOrDefault(productId, Set.of());
            if (!shots.contains("P1")) {
                continue; // the pipeline cannot run yet
            }
            remaining.merge(JobStep.WHITE_MAIN, 1, Integer::sum);
            long angles = pipelineProperties.getAngleShots().stream().filter(shots::contains)
                    .count();
            if (angles > 0) {
                remaining.merge(JobStep.WHITE_ANGLE, (int) angles, Integer::sum);
            }
            if (shots.contains("P8")) {
                remaining.merge(JobStep.INBOX, 1, Integer::sum);
            }
            boolean sceneSource = pipelineProperties.getSceneSourceShots().stream()
                    .anyMatch(shots::contains);
            if (sceneSource) {
                remaining.merge(JobStep.SCENE, sceneCount, Integer::sum);
            }
        }
        return remaining;
    }

    /** Average of the last 50 successful jobs per step, configured default else. */
    private Map<JobStep, Double> gpuAverages(long tenantId, Map<JobStep, Integer> remaining) {
        Map<JobStep, Double> averages = new LinkedHashMap<>();
        jdbcTemplate.query("select step, avg(gpu_seconds) as avg from (select step, gpu_seconds, "
                + "row_number() over (partition by step order by id desc) as rn from "
                + "generation_job where tenant_id = ? and status = 'SUCCEEDED' and gpu_seconds "
                + "is not null) recent where rn <= 50 group by step",
                rs -> {
                    averages.put(JobStep.valueOf(rs.getString("step")),
                            rs.getBigDecimal("avg").doubleValue());
                },
                tenantId);
        for (JobStep step : remaining.keySet()) {
            averages.computeIfAbsent(step,
                    key -> properties.getDefaultGpuSeconds().getOrDefault(key.name(), 0.0));
        }
        return averages;
    }

    /** Average latency of the last 50 successful calls of one purpose, in seconds. */
    private double llmAverageSeconds(long tenantId, String purpose) {
        Double millis = jdbcTemplate.queryForObject("select avg(latency_ms) from (select latency_ms, "
                + "row_number() over (order by id desc) as rn from llm_call where tenant_id = ? "
                + "and purpose = ? and status = 'OK' and latency_ms is not null) recent where "
                + "rn <= 50", Double.class, tenantId, purpose);
        return millis == null ? 0.0 : millis / 1000.0;
    }

    /**
     * LLM cost of the batch's SKUs since the batch was created: the calls
     * linked from their fact sheets and from the copy assets' provenance.
     */
    private BigDecimal llmCostUsd(long tenantId, BatchEntity batch) {
        return jdbcTemplate.queryForObject("select coalesce(sum(c.cost_usd), 0) from llm_call c "
                + "where c.tenant_id = ? and c.created_at >= ? and (c.id in (select fs.llm_call_id "
                + "from product_fact_sheet fs where fs.llm_call_id is not null and fs.product_id in "
                + "(select product_id from content_batch_item where batch_id = ?)) or c.id in "
                + "(select (a.provenance_json ->> 'llmCallId')::bigint from asset a where a.product_id "
                + "in (select product_id from content_batch_item where batch_id = ?) and "
                + "a.provenance_json ->> 'llmCallId' ~ '^[0-9]+$'))",
                BigDecimal.class, tenantId, batch.getCreatedAt(), batch.getId(), batch.getId());
    }

    private double gpuSecondsUsed(long tenantId, long batchId) {
        Double seconds = jdbcTemplate.queryForObject("select coalesce(sum(gpu_seconds), 0) from "
                + "generation_job where tenant_id = ? and batch_id = ?", Double.class, tenantId,
                batchId);
        return seconds == null ? 0.0 : seconds;
    }
}