package com.kiano.content.batch;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.asset.ReviewService;
import com.kiano.content.asset.ReviewService.ReviewItem;
import com.kiano.content.batch.BatchStageResolver.ItemSnapshot;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactSheetService.FactSheetView;
import com.kiano.content.facts.FactsJson;
import com.kiano.content.media.SourceMediaEntity;
import com.kiano.content.media.SourceMediaMapper;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The two keyboard workbenches of a batch (C6 Task 6): the SKUs waiting for
 * the human fact lock and the SKUs whose assets are ready to review. Both
 * queues are derived from the same stages the orchestrator uses, ordered by the
 * item position, and page with {@code after=<productId>}.
 */
@Service
public class BatchQueueService {

    /** One SKU waiting for the human fact lock. */
    public record FactQueueItem(long productId, String sku, String name, FactSheetView draft,
            List<String> plateUrls, List<String> promoUrls, Map<String, String> fieldSources) {
    }

    /** One SKU whose assets wait for review; flagged assets sort first. */
    public record ReviewQueueItem(long productId, String sku, String name,
            List<ReviewItem> assets, @Nullable FactsJson facts) {
    }

    private static final Duration URL_TTL = Duration.ofMinutes(15);
    private static final int DEFAULT_LIMIT = 20;

    private final BatchService batchService;
    private final BatchSnapshotReader snapshotReader;
    private final FactSheetService factSheets;
    private final ReviewService reviewService;
    private final SourceMediaMapper sourceMediaMapper;
    private final ProductCatalog productCatalog;
    private final ObjectStorage storage;

    public BatchQueueService(BatchService batchService, BatchSnapshotReader snapshotReader,
            FactSheetService factSheets, ReviewService reviewService,
            SourceMediaMapper sourceMediaMapper, ProductCatalog productCatalog,
            ObjectStorage storage) {
        this.batchService = batchService;
        this.snapshotReader = snapshotReader;
        this.factSheets = factSheets;
        this.reviewService = reviewService;
        this.sourceMediaMapper = sourceMediaMapper;
        this.productCatalog = productCatalog;
        this.storage = storage;
    }

    /** SKUs whose draft facts wait for the human lock, in position order. */
    public List<FactQueueItem> factQueue(long tenantId, long batchId,
            @Nullable Long afterProductId, int limit) {
        List<FactQueueItem> queue = new ArrayList<>();
        for (BatchItemEntity item : itemsInStage(tenantId, batchId, BatchStage.AWAITING_FACT_LOCK,
                afterProductId, limit)) {
            ProductView product = productCatalog.findById(tenantId, item.getProductId())
                    .orElse(null);
            FactSheetView draft = factSheets.current(tenantId, item.getProductId()).orElse(null);
            if (product == null || draft == null) {
                continue;
            }
            Map<String, String> sources = new LinkedHashMap<>();
            draft.fieldSources().forEach((field, source) -> sources.put(field, source.name()));
            List<String> plates = new ArrayList<>();
            List<String> promos = new ArrayList<>();
            for (SourceMediaEntity media : acceptedMedia(tenantId, item.getProductId())) {
                if ("P5".equals(media.getShotCode())) {
                    plates.add(presigned(media));
                } else if ("PROMO".equals(media.getShotCode())) {
                    promos.add(presigned(media));
                }
            }
            queue.add(new FactQueueItem(item.getProductId(), product.sku(), product.name(), draft,
                    plates, promos, sources));
        }
        return queue;
    }

    /**
     * SKUs whose assets wait for review: nothing of theirs is still generating
     * and at least one asset is IN_REVIEW (the stage IN_REVIEW). The assets
     * reuse the review-board order, so precheck-flagged ones come first.
     */
    public List<ReviewQueueItem> reviewQueue(long tenantId, long batchId,
            @Nullable Long afterProductId, int limit) {
        List<ReviewQueueItem> queue = new ArrayList<>();
        for (BatchItemEntity item : itemsInStage(tenantId, batchId, BatchStage.IN_REVIEW,
                afterProductId, limit)) {
            ProductView product = productCatalog.findById(tenantId, item.getProductId())
                    .orElse(null);
            List<ReviewItem> assets = reviewService.list(tenantId, item.getProductId(),
                    "IN_REVIEW", null);
            if (product == null || assets.isEmpty()) {
                continue;
            }
            FactsJson facts = factSheets.locked(tenantId, item.getProductId())
                    .map(FactSheetView::facts).orElse(null);
            queue.add(new ReviewQueueItem(item.getProductId(), product.sku(), product.name(),
                    assets, facts));
        }
        return queue;
    }

    /** The batch's items whose derived stage is {@code stage}, paged by position. */
    private List<BatchItemEntity> itemsInStage(long tenantId, long batchId, BatchStage stage,
            @Nullable Long afterProductId, int limit) {
        if (batchService.find(tenantId, batchId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Batch not found");
        }
        List<BatchItemEntity> items = batchService.items(tenantId, batchId);
        Map<Long, ItemSnapshot> snapshots = snapshotReader.read(tenantId, items);
        int afterPosition = -1;
        if (afterProductId != null) {
            afterPosition = items.stream()
                    .filter(item -> item.getProductId().equals(afterProductId))
                    .map(BatchItemEntity::getPosition).findFirst().orElse(-1);
        }
        int effectiveLimit = limit <= 0 ? DEFAULT_LIMIT : limit;
        List<BatchItemEntity> selected = new ArrayList<>();
        for (BatchItemEntity item : items) {
            if (item.getPosition() <= afterPosition) {
                continue;
            }
            ItemSnapshot snapshot = snapshots.get(item.getProductId());
            if (snapshot == null
                    || BatchStageResolver.resolve(snapshot).stage() != stage) {
                continue;
            }
            selected.add(item);
            if (selected.size() >= effectiveLimit) {
                break;
            }
        }
        return selected;
    }

    private List<SourceMediaEntity> acceptedMedia(long tenantId, long productId) {
        return sourceMediaMapper.selectList(Wrappers.<SourceMediaEntity>lambdaQuery()
                .eq(SourceMediaEntity::getTenantId, tenantId)
                .eq(SourceMediaEntity::getProductId, productId)
                .eq(SourceMediaEntity::getStatus, "ACCEPTED")
                .orderByAsc(SourceMediaEntity::getId));
    }

    private String presigned(SourceMediaEntity media) {
        String key = media.getThumbObjectKey() != null ? media.getThumbObjectKey()
                : media.getObjectKey();
        return storage.presignGet(key, URL_TTL).toString();
    }
}