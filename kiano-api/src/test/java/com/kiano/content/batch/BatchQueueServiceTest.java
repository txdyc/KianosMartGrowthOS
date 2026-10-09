package com.kiano.content.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.content.batch.BatchQueueService.FactQueueItem;
import com.kiano.content.batch.BatchQueueService.ReviewQueueItem;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.workerprotocol.JobStep;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * BatchQueueService (C6 Task 6): the fact queue only carries SKUs waiting for
 * the human lock (position order, presigned plates/promos, field sources), and
 * the review queue skips SKUs that still generate assets, with the
 * precheck-flagged assets first.
 */
class BatchQueueServiceTest extends BatchTestSupport {

    @Autowired
    private BatchQueueService queue;

    @Test
    void factQueue_onlyAwaitingLock_inPositionOrder_withPresignedPlates() {
        long first = readyProduct("MG-STD1");
        draftFacts(first, "{\"model\":\"P5\",\"warranty\":\"PROMO\"}");
        long second = readyProduct("MG-STD2");
        draftFacts(second, "{\"model\":\"P5\"}");
        promo(second, "ACCEPTED");
        long third = readyProduct("MG-STD3");
        long batchId = batches.create(operator, "Spring", List.of(first, second, third), false)
                .id();

        List<FactQueueItem> queueItems = queue.factQueue(tenantId, batchId, null, 10);

        assertThat(queueItems).extracting(FactQueueItem::productId).containsExactly(first, second);
        FactQueueItem firstItem = queueItems.get(0);
        assertThat(firstItem.sku()).isEqualTo("MG-STD1");
        assertThat(firstItem.name()).isEqualTo("Product MG-STD1");
        assertThat(firstItem.draft().version()).isEqualTo(1);
        assertThat(firstItem.fieldSources()).containsEntry("model", "P5")
                .containsEntry("warranty", "PROMO");
        assertThat(firstItem.plateUrls()).hasSize(1);
        assertThat(firstItem.plateUrls().get(0)).startsWith("http");
        assertThat(firstItem.promoUrls()).isEmpty();
        assertThat(queueItems.get(1).promoUrls()).hasSize(1);
        assertThat(queueItems.get(1).plateUrls()).hasSize(1);

        // paging: after the first SKU only the second one follows
        assertThat(queue.factQueue(tenantId, batchId, first, 10))
                .extracting(FactQueueItem::productId).containsExactly(second);
    }

    @Test
    void reviewQueue_skipsSkusStillGenerating_flaggedAssetsFirst() {
        long ready = readyProduct("MG-STD1");
        lockFacts(ready);
        long plain = asset(ready, "PAGE_MAIN", "main", "IN_REVIEW", null, "{\"flags\":[]}");
        long flagged = asset(ready, "PAGE_ANGLE", "P2", "IN_REVIEW", null,
                "{\"flags\":[\"AI_TEXT\"]}");

        // still generating: an IN_REVIEW asset, but a queued job as well
        long generating = readyProduct("MG-STD2");
        lockFacts(generating);
        asset(generating, "PAGE_MAIN", "main", "IN_REVIEW", null, "{\"flags\":[]}");
        long runId = runs.create(tenantId, generating, null);
        jobs.create(tenantId, generating, runId, JobStep.CUTOUT, "P1", Map.of(), List.of(),
                GenerationJobStore.JobPriority.INTERACTIVE);

        long batchId = batches.create(operator, "Spring", List.of(ready, generating), false).id();

        List<ReviewQueueItem> queueItems = queue.reviewQueue(tenantId, batchId, null, 10);

        assertThat(queueItems).extracting(ReviewQueueItem::productId).containsExactly(ready);
        ReviewQueueItem item = queueItems.get(0);
        assertThat(item.sku()).isEqualTo("MG-STD1");
        assertThat(item.assets()).extracting(asset -> asset.assetId())
                .containsExactly(flagged, plain);
        assertThat(item.assets().get(0).flags()).isNotEmpty();
        assertThat(item.facts()).isNotNull();
        assertThat(item.facts().model()).isEqualTo("Kiano");
    }
}