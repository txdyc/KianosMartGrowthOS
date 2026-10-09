package com.kiano.content.batch;

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
import org.springframework.web.bind.annotation.RestController;

/**
 * Batch endpoints (C6 Task 2/4): the batch list, the creation page's candidate
 * SKUs (STANDARD top-level products with their shot state), the pause / resume
 * / cancel actions and the dashboard's progress snapshot.
 */
@RestController
@RequestMapping("/api/v1/content/batches")
public class BatchController {

    /** Creation form body: name, the selected SKUs and the night-only flag. */
    public record CreateBatchRequest(String name, List<Long> productIds,
            @Nullable Boolean nightOnly) {
    }

    private final BatchService batchService;
    private final BatchProgressService progressService;

    public BatchController(BatchService batchService, BatchProgressService progressService) {
        this.batchService = batchService;
        this.progressService = progressService;
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