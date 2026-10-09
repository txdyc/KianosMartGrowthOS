package com.kiano.content.batch;

import com.kiano.content.batch.BatchPublishService.ProductionResult;
import com.kiano.content.batch.BatchQueueService.FactQueueItem;
import com.kiano.content.batch.BatchQueueService.ReviewQueueItem;
import com.kiano.platform.auth.CurrentUser;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Batch endpoints (C6 Task 2/4/6): the batch list, the creation page's
 * candidate SKUs (STANDARD top-level products with their shot state), the
 * pause / resume / cancel actions, the dashboard's progress snapshot, the two
 * workbench queues, the OWNER bulk production publish and the staging retry.
 */
@RestController
@RequestMapping("/api/v1/content/batches")
public class BatchController {

    /** Creation form body: name, the selected SKUs and the night-only flag. */
    public record CreateBatchRequest(String name, List<Long> productIds,
            @Nullable Boolean nightOnly) {
    }

    /** Production body: the READY SKUs the OWNER selected. */
    public record PublishProductionRequest(List<Long> productIds) {
    }

    private final BatchService batchService;
    private final BatchProgressService progressService;
    private final BatchQueueService queueService;
    private final BatchPublishService publishService;

    public BatchController(BatchService batchService, BatchProgressService progressService,
            BatchQueueService queueService, BatchPublishService publishService) {
        this.batchService = batchService;
        this.progressService = progressService;
        this.queueService = queueService;
        this.publishService = publishService;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<BatchService.BatchView> list(CurrentUser user) {
        return batchService.list(user.tenantId());
    }

    @GetMapping("/candidates")
    @PreAuthorize("hasRole('OPERATOR')")
    public List<BatchService.Candidate> candidates(CurrentUser user) {
        return batchService.candidates(user.tenantId());
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<BatchService.BatchView> create(CurrentUser user,
            @RequestBody CreateBatchRequest request) {
        BatchService.BatchView created = batchService.create(user, request.name(),
                request.productIds() == null ? List.of() : request.productIds(),
                Boolean.TRUE.equals(request.nightOnly()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}/progress")
    @PreAuthorize("hasRole('VIEWER')")
    public BatchProgressService.BatchProgress progress(CurrentUser user,
            @PathVariable("id") long batchId) {
        return progressService.progress(user.tenantId(), batchId);
    }

    @GetMapping("/{id}/fact-queue")
    @PreAuthorize("hasRole('OPERATOR')")
    public List<FactQueueItem> factQueue(CurrentUser user, @PathVariable("id") long batchId,
            @RequestParam(value = "after", required = false) Long after,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return queueService.factQueue(user.tenantId(), batchId, after, limit);
    }

    @GetMapping("/{id}/review-queue")
    @PreAuthorize("hasRole('OPERATOR')")
    public List<ReviewQueueItem> reviewQueue(CurrentUser user, @PathVariable("id") long batchId,
            @RequestParam(value = "after", required = false) Long after,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return queueService.reviewQueue(user.tenantId(), batchId, after, limit);
    }

    @PostMapping("/{id}/publish-production")
    @PreAuthorize("hasRole('OWNER')")
    public ProductionResult publishProduction(CurrentUser user, @PathVariable("id") long batchId,
            @RequestBody PublishProductionRequest request) {
        return publishService.publishProduction(user, batchId,
                request.productIds() == null ? List.of() : request.productIds());
    }

    @PostMapping("/{id}/items/{productId}/retry-staging")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Void> retryStaging(CurrentUser user, @PathVariable("id") long batchId,
            @PathVariable("productId") long productId) {
        publishService.retryStaging(user, batchId, productId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasRole('OPERATOR')")
    public BatchService.BatchView pause(CurrentUser user, @PathVariable("id") long batchId) {
        return batchService.pause(user, batchId);
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasRole('OPERATOR')")
    public BatchService.BatchView resume(CurrentUser user, @PathVariable("id") long batchId) {
        return batchService.resume(user, batchId);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('OPERATOR')")
    public BatchService.BatchView cancel(CurrentUser user, @PathVariable("id") long batchId) {
        return batchService.cancel(user, batchId);
    }
}