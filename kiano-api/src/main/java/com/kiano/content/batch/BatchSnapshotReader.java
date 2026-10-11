package com.kiano.content.batch;

import com.kiano.content.policy.PolicyService;
import com.kiano.content.profile.ProductProfileService;
import com.kiano.content.publish.PublishRules;
import com.kiano.content.shots.ContentProductSummary;
import com.kiano.content.shots.ShotStatusService;
import java.util.HashMap;
import java.util.HashSet;
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
 * restricted to the batch's products, never one per SKU. The result is exactly
 * what {@link BatchStageResolver} needs - nothing about the stage is stored.
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
        if (productIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Long[] ids = productIds.toArray(Long[]::new);
        // payload ->> 'productId' is text
        String[] idTexts = productIds.stream().map(String::valueOf).toArray(String[]::new);
        Map<Long, Boolean> topLevel = new HashMap<>();
        jdbcTemplate.query("select id, parent_id from product where tenant_id = ? and id = any(?)",
                rs -> {
                    topLevel.put(rs.getLong("id"), rs.getObject("parent_id") == null);
                }, tenantId, ids);
        Map<Long, com.kiano.content.ContentTier> tiers = profileService.tiersFor(tenantId);
        Map<Long, ContentProductSummary> summaries = new HashMap<>();
        for (ContentProductSummary summary : shotStatusService.summaries(tenantId, null, null)) {
            summaries.put(summary.productId(), summary);
        }
        Set<Long> factSources = new HashSet<>(jdbcTemplate.queryForList(
                "select distinct product_id from source_media where tenant_id = ? "
                        + "and product_id = any(?) "
                        + "and status = 'ACCEPTED' and shot_code in ('P5','PROMO')",
                Long.class, tenantId, ids));
        Map<Long, String> latestRunStatus = new HashMap<>();
        Map<Long, Long> latestRunId = new HashMap<>();
        jdbcTemplate.query("select id, product_id, status from generation_run "
                + "where tenant_id = ? and product_id = any(?) and kind = 'IMAGE_SET' order by id",
                rs -> {
                    long productId = rs.getLong("product_id");
                    latestRunId.put(productId, rs.getLong("id"));
                    latestRunStatus.put(productId, rs.getString("status"));
                }, tenantId, ids);
        Map<Long, Integer> failedJobsByRun = countsByKey(
                "select run_id as k, count(*) as n from generation_job where tenant_id = ? "
                        + "and product_id = any(?) and status = 'FAILED' group by 1",
                tenantId, ids);
        Map<Long, String> factStatus = new HashMap<>();
        jdbcTemplate.query("select product_id, status from product_fact_sheet where tenant_id = ? "
                + "and product_id = any(?) and status in ('DRAFT','LOCKED') order by id", rs -> {
                    long productId = rs.getLong("product_id");
                    // LOCKED wins when both rows exist
                    if (!"LOCKED".equals(factStatus.get(productId))) {
                        factStatus.put(productId, rs.getString("status"));
                    }
                }, tenantId, ids);
        Map<Long, String> latestDraftTask = new HashMap<>();
        jdbcTemplate.query("select payload ->> 'productId' as pid, status from platform_task "
                + "where tenant_id = ? and type = 'FACT_DRAFT' "
                + "and payload ->> 'productId' = any(?) order by id", rs -> {
                    latestDraftTask.put(Long.parseLong(rs.getString("pid")), rs.getString("status"));
                }, tenantId, idTexts);
        Map<Long, Set<String>> liveSpecs = new HashMap<>();
        Map<Long, Long> liveAngles = new HashMap<>();
        Map<Long, Integer> inReview = new HashMap<>();
        // per (spec, variant) slot: the newest status, and whether any version is live
        Map<Long, Map<Slot, String>> newestBySlot = new HashMap<>();
        Map<Long, Set<Slot>> liveSlots = new HashMap<>();
        jdbcTemplate.query("select product_id, spec_code, variant, status from asset "
                + "where tenant_id = ? and product_id = any(?) order by id", rs -> {
                    long productId = rs.getLong("product_id");
                    String status = rs.getString("status");
                    String spec = rs.getString("spec_code");
                    Slot slot = new Slot(spec, rs.getString("variant"));
                    newestBySlot.computeIfAbsent(productId, key -> new HashMap<>()).put(slot, status);
                    if (PublishRules.LIVE_STATUSES.contains(status)) {
                        liveSpecs.computeIfAbsent(productId, key -> new LinkedHashSet<>()).add(spec);
                        liveSlots.computeIfAbsent(productId, key -> new HashSet<>()).add(slot);
                        if ("PAGE_ANGLE".equals(spec)) {
                            liveAngles.merge(productId, 1L, Long::sum);
                        }
                    } else if ("IN_REVIEW".equals(status)) {
                        inReview.merge(productId, 1, Integer::sum);
                    }
                }, tenantId, ids);
        Map<Long, Integer> generating = countsByKey(
                "select product_id as k, count(*) as n from generation_job where tenant_id = ? "
                        + "and product_id = any(?) "
                        + "and status in ('QUEUED','LEASED','WAITING_EXECUTOR') group by 1",
                tenantId, ids);
        Map<Long, Integer> generatingTasks = new HashMap<>();
        jdbcTemplate.query("select payload ->> 'productId' as pid, count(*) as n from platform_task "
                + "where tenant_id = ? and type in ('COPY_GENERATE','TEMPLATE_RENDER') "
                + "and payload ->> 'productId' = any(?) "
                + "and status in ('QUEUED','RUNNING') group by 1", rs -> {
                    generatingTasks.put(Long.parseLong(rs.getString("pid")), rs.getInt("n"));
                }, tenantId, idTexts);
        Map<Long, PublicationState> staging = new HashMap<>();
        Map<Long, PublicationState> production = new HashMap<>();
        jdbcTemplate.query("select product_id, environment, status, error from publication "
                + "where tenant_id = ? and product_id = any(?) and target = 'WOO_PRODUCT' "
                + "order by id desc", rs -> {
                    long productId = rs.getLong("product_id");
                    Map<Long, PublicationState> target =
                            "STAGING".equals(rs.getString("environment")) ? staging : production;
                    target.putIfAbsent(productId, new PublicationState(rs.getString("status"),
                            rs.getString("error")));
                }, tenantId, ids);
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
                    productionState == null ? null : productionState.error(),
                    rejectedSpecs(newestBySlot.getOrDefault(productId, Map.of()),
                            liveSlots.getOrDefault(productId, Set.of()))));
        }
        return snapshots;
    }

    /** Specs with a slot whose newest version was rejected and that has no live version. */
    private static Set<String> rejectedSpecs(Map<Slot, String> newestBySlot, Set<Slot> liveSlots) {
        Set<String> rejected = new LinkedHashSet<>();
        newestBySlot.forEach((slot, status) -> {
            if ("REJECTED".equals(status) && !liveSlots.contains(slot)) {
                rejected.add(slot.spec());
            }
        });
        return rejected;
    }

    private record Slot(String spec, @Nullable String variant) {
    }

    private record PublicationState(String status, @Nullable String error) {
    }

    /** One grouped count query, keyed by a {@code k} column. */
    private Map<Long, Integer> countsByKey(String sql, Object... args) {
        Map<Long, Integer> counts = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            counts.put(rs.getLong("k"), rs.getInt("n"));
        }, args);
        return counts;
    }
}
