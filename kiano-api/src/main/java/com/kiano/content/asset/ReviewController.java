package com.kiano.content.asset;

import com.kiano.content.asset.ReviewService.DecisionResult;
import com.kiano.content.asset.ReviewService.ReviewItem;
import com.kiano.platform.auth.CurrentUser;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Review endpoints: the asset list for the board (VIEWER), single-asset
 * decisions (OPERATOR) and approve-remaining (OPERATOR).
 */
@RestController
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/content/assets")
    @PreAuthorize("hasRole('VIEWER')")
    public List<ReviewItem> list(CurrentUser user, @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String status) {
        return service.list(user, productId, status);
    }

    @PostMapping("/api/v1/content/assets/{id}/review")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Object> decide(CurrentUser user, @PathVariable long id,
            @RequestBody ReviewRequest request) {
        DecisionResult result = service.decide(user, id, request.decision(),
                request.reasonCodes() == null ? List.of() : request.reasonCodes(),
                request.comment());
        if (result.runId() != null) {
            return ResponseEntity.accepted().body(Map.of("runId", result.runId()));
        }
        return ResponseEntity.ok(result.item());
    }

    @PostMapping("/api/v1/content/products/{id}/review/approve-remaining")
    @PreAuthorize("hasRole('OPERATOR')")
    public Map<String, Integer> approveRemaining(CurrentUser user, @PathVariable long id) {
        return Map.of("approved", service.approveRemaining(user, id));
    }

    record ReviewRequest(ReviewService.Decision decision, List<RejectReason> reasonCodes,
            @Nullable String comment) {
    }
}
