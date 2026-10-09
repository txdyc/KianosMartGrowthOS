package com.kiano.content.batch;

import com.kiano.content.policy.PolicyService;
import com.kiano.content.profile.ProductProfileService;
import com.kiano.content.publish.PublishRules;
import com.kiano.content.shots.ContentProductSummary;
import com.kiano.content.shots.ShotStatusService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the state of every batch item in bulk (C6 Task 3): one query per table
 * for the whole tenant, never one per SKU. The result is exactly what
 * {@link BatchStageResolver} needs - nothing about the stage is stored.
 */
@Component
public class BatchSnapshotReader {

    private final JdbcTemplate jdbcTemplate;
    private final ProductProfileService profileService;
    private final ShotStatusService shotStatusService;
    private final PolicyService policyService;

    public BatchSnapshotReader(JdbcTemplate jdbcTemplate, ProductProfileService profileService,
            ShotStatusService shotStatusService, PolicyService policyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.profileService = profileService;
        this.shotStatusService = shotStatusService;
        this.policyService = policyService;
    }

    /** The snapshot of every given product, keyed by product id. */
    public Map<Long, BatchStageResolver.ItemSnapshot> read(long tenantId,
            List<BatchItemEntity> items) {
        Set<Long> productIds = new LinkedHashSet<>();
        items.forEach(item -> productIds.add(item.getProductId()));
        Map<Long, Boolean> topLevel = new HashMap<>();
        jdbcTemplate.query("select id, parent_id from product where tenant_id = ?",
                rs -> {
                    long id = rs.getLong("id");
                    if (productIds.contains(id)) {
                        topLevel.put(id, rs.getObject("parent_id") == null);
                    }
                }, tenantId);
        Map<Long, com.kiano.content.ContentTier> tiers = profileService.tiersFor(tenantId);
        Map<Long, ContentProductSummary> summaries = new HashMap<>();
        for (ContentProductSummary summary : shotStatusService.summaries(tenantId, null, null)) {
            summaries.put(summary.productId(), summary);
        }
        Set<Long> factSources = new java.util.HashSet<>(jdbcTemplate.queryForList(
                "select distinct product_id from source_media where tenant_id = ? "
                        + "and status = 'ACCEPTED' and shot_code in ('P5','PROMO')",
                Long.class, tenantId));
        Map<Long, String> latestRunStatus = new HashMap<>();
        Map<Long, Long> latestRunId = new HashMap<>();
        jdbcTemplate.query("select id, product_id, status from generation_run "
                + "where tenant_id = ? and kind = 'IMAGE_SET' order by id", rs -> {
                    long productId = rs.getLong("product_id");
                    latestRunId.put(productId, rs.getLong("id"));
                    latestRunStatus.put(productId, rs.getString("status"));
                }, tenantId);
        Map<Long, Integer> failedJobsByRun = countsByKey(
                "select run_id as k, count(*) as n from generation_job where tenant_id = ? "
                        + "and status = 'FAILED' group by 1", tenantId);
        Map<Long, String> factStatus = new HashMap<>();
        jdbcTemplate.query("select product_id, status from product_fact_sheet where tenant_id = ? "
                + "and status in ('DRAFT','LOCKED') order by id", rs -> {
                    long productId = rs.getLong("product_id");
                    // LOCKED wins when both rows exist
                    if (!"LOCKED".equals(factStatus.get(productId))) {
                        factStatus.put(productId, rs.getString("status"));
                    }
                }, tenantId);
        Map<Long, String> latestDraftTask = new HashMap<>();
        jdbcTemplate.query("select payload ->> 'productId' as pid, status from platform_task "
                + "where tenant_id = ? and type = 'FACT_DRAFT' order by id", rs -> {
                    String pid = rs.getString("pid");
                    if (pid != null) {
                        latestDraftTask.put(Long.parseLong(pid), rs.getString("status"));
                    }
                }, tenantId);
        Map<Long, Set<String>> liveSpecs = new HashMap<>();
        Map<Long, Long> liveAngles = new HashMap<>();
        Map<Long, Integer> inReview = new HashMap<>();
        jdbcTemplate.query("select product_id, spec_code, status from asset where tenant_id = ? "
                + "order by id", rs -> {
                    long productId = rs.getLong("product_id");
                    String status = rs.getString("status");
                    if (PublishRules.LIVE_STATUSES.contains(status)) {
                        String spec = rs.getString("spec_code");
                        liveSpecs.computeIfAbsent(productId, key -> new LinkedHashSet<>()).add(spec);
                        if ("PAGE_ANGLE".equals(spec)) {
                            liveAngles.merge(productId, 1L, Long::sum);
                        }
                    } else if ("IN_REVIEW".equals(status)) {
                        inReview.merge(productId, 1, Integer::sum);
                    }
                }, tenantId);
        Map<Long, Integer> generating = countsByKey(
                "select product_id as k, count(*) as n from generation_job where tenant_id = ? "
                        + "and status in ('QUEUED','LEASED','WAITING_EXECUTOR') group by 1", tenantId);
        Map<Long, Integer> generatingTasks = new HashMap<>();
        jdbcTemplate.query("select payload ->> 'productId' as pid, count(*) as n from platform_task "
                + "where tenant_id = ? and type in ('COPY_GENERATE','TEMPLATE_RENDER') "
                + "and status in ('QUEUED','RUNNING') group by 1", rs -> {
                    String pid = rs.getString("pid");
                    if (pid != null) {
                        generatingTasks.put(Long.parseLong(pid), rs.getInt("n"));
                    }
                }, tenantId);
        Map<Long, PublicationState> staging = new HashMap<>();
        Map<Long, PublicationState> production = new HashMap<>();
        jdbcTemplate.query("select product_id, environment, status, error from publication "
                + "where tenant_id = ? and target = 'WOO_PRODUCT' order by id desc", rs -> {
                    long productId = rs.getLong("product_id");
                    Map<Long, PublicationState> target =
                            "STAGING".equals(rs.getString("environment")) ? staging : production;
                    target.putIfAbsent(productId, new PublicationState(rs.getString("status"),
                            rs.getString("error")));
                }, tenantId);
        boolean policyComplete = policyService.current(tenantId)
                .map(view -> view.complete()).orElse(false);

        Map<Long, BatchStageResolver.ItemSnapshot> snapshots = new LinkedHashMap<>();
        for (long productId : productIds) {
            Set<String> specs = liveSpecs.getOrDefault(productId, Set.of());
            long angles = liveAngles.getOrDefault(productId, 0L);
            List<String> missing = PublishRules.missingRequired(specs, angles);
            ContentProductSummary summary = summaries.get(productId);
            String draftTask = latestDraftTask.get(productId);
            PublicationState stagingState = staging.get(productId);
            PublicationState productionState = production.get(productId);
            snapshots.put(productId, new BatchStageResolver.ItemSnapshot(
                    productId,
                    topLevel.containsKey(productId),
                    Boolean.TRUE.equals(topLevel.get(productId)),
                    tiers.getOrDefault(productId, com.kiano.content.ContentTier.STANDARD),
                    summary != null && summary.complete(),
                    summary != null && summary.reshoot() > 0,
                    factSources.contains(productId),
                    latestRunStatus.get(productId),
                    failedJobsByRun.getOrDefault(latestRunId.get(productId), 0),
                    factStatus.get(productId),
                    "QUEUED".equals(draftTask) || "RUNNING".equals(draftTask),
                    "FAILED".equals(draftTask),
                    policyComplete,
                    specs,
                    angles,
                    inReview.getOrDefault(productId, 0),
                    generating.getOrDefault(productId, 0)
                            + generatingTasks.getOrDefault(productId, 0),
                    missing,
                    stagingState == null ? null : stagingState.status(),
                    stagingState == null ? null : stagingState.error(),
                    productionState == null ? null : productionState.status(),
                    productionState == null ? null : productionState.error()));
        }
        return snapshots;
    }

    private record PublicationState(String status, @Nullable String error) {
    }

    /** One grouped count query, keyed by a {@code k} column. */
    private Map<Long, Integer> countsByKey(String sql, long tenantId) {
        Map<Long, Integer> counts = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            counts.put(rs.getLong("k"), rs.getInt("n"));
        }, tenantId);
        return counts;
    }
}