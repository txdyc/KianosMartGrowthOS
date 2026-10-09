package com.kiano.content.batch;

import com.kiano.content.ContentTier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Derives the stage of one batch item from a {@link ItemSnapshot} (C6 Task 3).
 * The image lane (shots → pipeline) and the fact lane (draft → lock) run in
 * parallel, and the item's stage is the slower of the two; once both lanes
 * have produced their content, the review and publish state decides the rest.
 * BLOCKED always wins, with one of the reason codes below.
 */
public final class BatchStageResolver {

    /** Everything the resolver sees about one item; read in bulk by the reader. */
    public record ItemSnapshot(
            long productId,
            boolean productExists,
            boolean topLevel,
            ContentTier tier,
            boolean shotsComplete,
            boolean reshoot,
            boolean hasFactSources,
            @Nullable String imageRunStatus,
            int failedImageJobs,
            /** DRAFT, LOCKED or null when the product has no fact sheet. */
            @Nullable String factStatus,
            boolean factDraftRunning,
            boolean factDraftFailed,
            boolean policyComplete,
            Set<String> liveSpecCodes,
            long liveAngleCount,
            int assetsInReview,
            int assetsGenerating,
            List<String> missingRequired,
            @Nullable String stagingStatus,
            @Nullable String stagingError,
            @Nullable String productionStatus,
            @Nullable String productionError) {
    }

    /** The derived stage plus the two lanes the dashboard shows. */
    public record Resolution(BatchStage stage, @Nullable String blockedReason,
            Map<String, Object> details, BatchStage imageLane, BatchStage factLane) {
    }

    private BatchStageResolver() {
    }

    public static Resolution resolve(ItemSnapshot s) {
        BatchStage imageLane = imageLane(s);
        BatchStage factLane = factLane(s);
        String blocked = blockedReason(s);
        if (blocked != null) {
            return new Resolution(BatchStage.BLOCKED, blocked, blockedDetails(s, blocked),
                    imageLane, factLane);
        }
        // the item is only as far as its slower lane
        BatchStage lane = imageLane.ordinal() <= factLane.ordinal() ? imageLane : factLane;
        if (lane.ordinal() < BatchStage.GENERATING.ordinal()) {
            return new Resolution(lane, null, Map.of(), imageLane, factLane);
        }
        return progressed(s, imageLane, factLane);
    }

    /** Shots → pipeline: WAITING_SHOTS until G1, IMAGES while it runs. */
    private static BatchStage imageLane(ItemSnapshot s) {
        if (!s.shotsComplete()) {
            return BatchStage.WAITING_SHOTS;
        }
        return "RUNNING".equals(s.imageRunStatus()) ? BatchStage.IMAGES : BatchStage.GENERATING;
    }

    /** Facts: drafting → awaiting the lock → locked (content generation). */
    private static BatchStage factLane(ItemSnapshot s) {
        if ("LOCKED".equals(s.factStatus())) {
            return BatchStage.GENERATING;
        }
        if ("DRAFT".equals(s.factStatus())) {
            return BatchStage.AWAITING_FACT_LOCK;
        }
        return BatchStage.FACTS_DRAFTING;
    }

    /** Both lanes produced their content: review and publish decide the stage. */
    private static Resolution progressed(ItemSnapshot s, BatchStage imageLane,
            BatchStage factLane) {
        BatchStage stage;
        if (s.assetsGenerating() > 0) {
            stage = BatchStage.GENERATING;
        } else if (s.assetsInReview() > 0) {
            stage = BatchStage.IN_REVIEW;
        } else if (!s.missingRequired().isEmpty()) {
            // everything reviewed but required content is still missing
            // (e.g. a rejected asset): generation has to run again
            stage = BatchStage.GENERATING;
        } else if (!s.policyComplete()) {
            return new Resolution(BatchStage.BLOCKED, "POLICY_INCOMPLETE", Map.of(),
                    imageLane, factLane);
        } else {
            stage = publishStage(s);
        }
        return new Resolution(stage, null, Map.of(), imageLane, factLane);
    }

    private static BatchStage publishStage(ItemSnapshot s) {
        if ("APPLIED".equals(s.productionStatus())) {
            return BatchStage.DONE;
        }
        if ("PENDING".equals(s.productionStatus())) {
            return BatchStage.PRODUCTION;
        }
        if ("APPLIED".equals(s.stagingStatus())) {
            return BatchStage.READY_FOR_PRODUCTION;
        }
        // no staging row yet, or one being published
        return BatchStage.STAGING;
    }

    private static @Nullable String blockedReason(ItemSnapshot s) {
        if (!s.productExists()) {
            return "PRODUCT_GONE";
        }
        if (!s.topLevel() || s.tier() != ContentTier.STANDARD) {
            return "NOW_HERO";
        }
        if (s.reshoot()) {
            return "SHOTS_RESHOOT";
        }
        if (s.failedImageJobs() > 0) {
            return "PIPELINE_FAILED";
        }
        if (s.factStatus() == null && !s.hasFactSources()) {
            return "NO_FACT_SOURCES";
        }
        if (s.factStatus() == null && s.factDraftFailed()) {
            return "FACT_DRAFT_FAILED";
        }
        if ("WOO_PRODUCT_NOT_FOUND".equals(s.stagingError())) {
            return "STAGING_PRODUCT_MISSING";
        }
        if ("FAILED".equals(s.stagingStatus()) || "FAILED".equals(s.productionStatus())) {
            return "PUBLISH_FAILED";
        }
        return null;
    }

    private static Map<String, Object> blockedDetails(ItemSnapshot s, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        switch (reason) {
            case "PIPELINE_FAILED" -> details.put("failedJobs", s.failedImageJobs());
            case "PUBLISH_FAILED" -> {
                if (s.stagingError() != null) {
                    details.put("stagingError", s.stagingError());
                }
                if (s.productionError() != null) {
                    details.put("productionError", s.productionError());
                }
            }
            case "STAGING_PRODUCT_MISSING" -> details.put("error", s.stagingError());
            default -> {
            }
        }
        return details;
    }
}