package com.kiano.content.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.content.ContentTier;
import com.kiano.content.batch.BatchStageResolver.ItemSnapshot;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * BatchStageResolver (C6 Task 3): the stage is derived from the snapshot - the
 * slower lane wins while the two lanes run, BLOCKED beats every stage, and a
 * relocked fact sheet moves the item back to content generation.
 */
class BatchStageResolverTest {

    /** A STANDARD SKU whose images are done and whose facts are locked. */
    private static ItemSnapshot ready(ContentTier tier, boolean shotsComplete,
            boolean reshoot, String imageRunStatus, String factStatus, boolean hasSources) {
        return new ItemSnapshot(7L, true, true, tier, shotsComplete, reshoot, hasSources,
                imageRunStatus, 0, factStatus, false, false, true,
                Set.of("PAGE_MAIN", "COPY_TITLE", "COPY_SHORT", "COPY_LONG"),
                3, 0, 0,
                List.of(), null, null, null, null);
    }

    @Test
    void lanesRunInParallel_stageIsTheSlowerLane() {
        // images still running while the facts wait for the lock: the slower
        // lane (IMAGES) is the stage
        ItemSnapshot imagesRunning = ready(ContentTier.STANDARD, true, false, "RUNNING", null,
                true);
        assertThat(BatchStageResolver.resolve(imagesRunning).stage())
                .isEqualTo(BatchStage.IMAGES);
        assertThat(BatchStageResolver.resolve(imagesRunning).factLane())
                .isEqualTo(BatchStage.FACTS_DRAFTING);

        // images done, facts drafted and waiting for the human: AWAITING_FACT_LOCK
        ItemSnapshot awaitingLock = ready(ContentTier.STANDARD, true, false, "DONE", "DRAFT",
                true);
        assertThat(BatchStageResolver.resolve(awaitingLock).stage())
                .isEqualTo(BatchStage.AWAITING_FACT_LOCK);
        assertThat(BatchStageResolver.resolve(awaitingLock).imageLane())
                .isEqualTo(BatchStage.GENERATING);

        // shots missing keeps the item at WAITING_SHOTS
        ItemSnapshot noShots = ready(ContentTier.STANDARD, false, false, null, null, true);
        assertThat(BatchStageResolver.resolve(noShots).stage())
                .isEqualTo(BatchStage.WAITING_SHOTS);
    }

    @Test
    void lockedFactsWithoutContent_isGenerating() {
        // facts are locked but no content exists yet: generation has to run
        ItemSnapshot locked = new ItemSnapshot(7L, true, true, ContentTier.STANDARD, true, false,
                true, "DONE", 0, "LOCKED", false, false, true, Set.of(), 0, 0, 0,
                List.of("PAGE_MAIN", "COPY_TITLE", "COPY_SHORT", "COPY_LONG"), null, null, null,
                null);

        BatchStageResolver.Resolution resolution = BatchStageResolver.resolve(locked);

        assertThat(resolution.stage()).isEqualTo(BatchStage.GENERATING);
        assertThat(resolution.imageLane()).isEqualTo(BatchStage.GENERATING);
        assertThat(resolution.factLane()).isEqualTo(BatchStage.GENERATING);
    }

    @Test
    void generatingAssetsThenInReviewThenStagingPipeline() {
        ItemSnapshot generating = new ItemSnapshot(7L, true, true, ContentTier.STANDARD, true,
                false, true, "DONE", 0, "LOCKED", false, false, true, Set.of("PAGE_MAIN"),
                0, 0, 3, List.of("COPY_LONG"), null, null, null, null);
        assertThat(BatchStageResolver.resolve(generating).stage()).isEqualTo(BatchStage.GENERATING);

        ItemSnapshot inReview = new ItemSnapshot(7L, true, true, ContentTier.STANDARD, true,
                false, true, "DONE", 0, "LOCKED", false, false, true,
                Set.of("PAGE_MAIN", "COPY_TITLE", "COPY_SHORT", "COPY_LONG"),
                3, 2, 0, List.of(), null, null, null, null);
        assertThat(BatchStageResolver.resolve(inReview).stage()).isEqualTo(BatchStage.IN_REVIEW);

        assertThat(BatchStageResolver.resolve(staging("APPLIED", "PENDING")).stage())
                .isEqualTo(BatchStage.PRODUCTION);
        assertThat(BatchStageResolver.resolve(staging(null, null)).stage())
                .isEqualTo(BatchStage.STAGING);
        assertThat(BatchStageResolver.resolve(staging("PENDING", null)).stage())
                .isEqualTo(BatchStage.STAGING);
        assertThat(BatchStageResolver.resolve(staging("APPLIED", null)).stage())
                .isEqualTo(BatchStage.READY_FOR_PRODUCTION);
        assertThat(BatchStageResolver.resolve(staging("APPLIED", "APPLIED")).stage())
                .isEqualTo(BatchStage.DONE);
    }

    private static ItemSnapshot staging(String stagingStatus, String productionStatus) {
        return new ItemSnapshot(7L, true, true, ContentTier.STANDARD, true, false, true, "DONE",
                0, "LOCKED", false, false, true,
                Set.of("PAGE_MAIN", "COPY_TITLE", "COPY_SHORT", "COPY_LONG"), 3, 0, 0,
                List.of(), stagingStatus, null, productionStatus, null);
    }

    @Test
    void reshootAfterImages_blockedShotsReshoot() {
        ItemSnapshot reshoot = ready(ContentTier.STANDARD, true, true, "DONE", "LOCKED", true);

        BatchStageResolver.Resolution resolution = BatchStageResolver.resolve(reshoot);

        assertThat(resolution.stage()).isEqualTo(BatchStage.BLOCKED);
        assertThat(resolution.blockedReason()).isEqualTo("SHOTS_RESHOOT");
    }

    @Test
    void tierChangedToHero_blockedNowHero() {
        ItemSnapshot hero = ready(ContentTier.HERO, true, false, "DONE", "LOCKED", true);

        BatchStageResolver.Resolution resolution = BatchStageResolver.resolve(hero);

        assertThat(resolution.stage()).isEqualTo(BatchStage.BLOCKED);
        assertThat(resolution.blockedReason()).isEqualTo("NOW_HERO");
    }

    @Test
    void productGoneAndPipelineFailedAreBlocked() {
        ItemSnapshot gone = new ItemSnapshot(7L, false, false, ContentTier.STANDARD, false,
                false, false, null, 0, null, false, false, false, Set.of(), 0, 0, 0, List.of(),
                null, null, null, null);
        assertThat(BatchStageResolver.resolve(gone).blockedReason()).isEqualTo("PRODUCT_GONE");

        ItemSnapshot failed = new ItemSnapshot(7L, true, true, ContentTier.STANDARD, true, false,
                true, "PARTIAL", 2, null, false, false, true, Set.of(), 0, 0, 0, List.of(),
                null, null, null, null);
        BatchStageResolver.Resolution resolution = BatchStageResolver.resolve(failed);
        assertThat(resolution.blockedReason()).isEqualTo("PIPELINE_FAILED");
        assertThat(resolution.details()).containsEntry("failedJobs", 2);
    }

    @Test
    void stagingWooNotFound_blockedStagingProductMissing() {
        ItemSnapshot missing = new ItemSnapshot(7L, true, true, ContentTier.STANDARD, true, false,
                true, "DONE", 0, "LOCKED", false, false, true,
                Set.of("PAGE_MAIN", "COPY_TITLE", "COPY_SHORT", "COPY_LONG"), 3, 0, 0,
                List.of(), "FAILED", "WOO_PRODUCT_NOT_FOUND", null, null);

        BatchStageResolver.Resolution resolution = BatchStageResolver.resolve(missing);

        assertThat(resolution.stage()).isEqualTo(BatchStage.BLOCKED);
        assertThat(resolution.blockedReason()).isEqualTo("STAGING_PRODUCT_MISSING");
    }

    @Test
    void noSourcesOrFailedDraft_areBlocked() {
        ItemSnapshot noSources = ready(ContentTier.STANDARD, true, false, "DONE", null, false);
        assertThat(BatchStageResolver.resolve(noSources).blockedReason())
                .isEqualTo("NO_FACT_SOURCES");

        ItemSnapshot draftFailed = new ItemSnapshot(7L, true, true, ContentTier.STANDARD, true,
                false, true, "DONE", 0, null, false, true, true, Set.of(), 0, 0, 0, List.of(),
                null, null, null, null);
        assertThat(BatchStageResolver.resolve(draftFailed).blockedReason())
                .isEqualTo("FACT_DRAFT_FAILED");
    }

    @Test
    void policyIncompleteBlocksOnlyWhenTheContentIsReady() {
        ItemSnapshot incomplete = new ItemSnapshot(7L, true, true, ContentTier.STANDARD, true,
                false, true, "DONE", 0, "LOCKED", false, false, false,
                Set.of("PAGE_MAIN", "COPY_TITLE", "COPY_SHORT", "COPY_LONG"), 3, 0, 0,
                List.of(), null, null, null, null);

        BatchStageResolver.Resolution resolution = BatchStageResolver.resolve(incomplete);

        assertThat(resolution.stage()).isEqualTo(BatchStage.BLOCKED);
        assertThat(resolution.blockedReason()).isEqualTo("POLICY_INCOMPLETE");
    }

    @Test
    void factsRelocked_backToGenerating() {
        // the relock archived the old copy and started a new copy run
        ItemSnapshot relocked = new ItemSnapshot(7L, true, true, ContentTier.STANDARD, true,
                false, true, "DONE", 0, "LOCKED", false, false, true,
                Set.of("PAGE_MAIN", "COPY_TITLE", "COPY_SHORT", "COPY_LONG"), 3, 0, 1,
                List.of(), "APPLIED", null, null, null);

        assertThat(BatchStageResolver.resolve(relocked).stage()).isEqualTo(BatchStage.GENERATING);
    }
}