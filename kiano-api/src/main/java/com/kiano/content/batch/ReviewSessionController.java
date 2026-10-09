package com.kiano.content.batch;

import com.kiano.content.batch.ReviewSessionService.SessionView;
import com.kiano.platform.auth.CurrentUser;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Review-session endpoints (C6 Task 5): the workbenches open a session per
 * SKU, beat their active time and finish it with the outcome.
 */
@RestController
@RequestMapping("/api/v1/content/review-sessions")
public class ReviewSessionController {

    public record StartRequest(long productId, @Nullable Long batchId, String kind) {
    }

    public record HeartbeatRequest(double activeSeconds) {
    }

    public record FinishRequest(double activeSeconds, String outcome) {
    }

    private final ReviewSessionService sessions;

    public ReviewSessionController(ReviewSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Long>> start(CurrentUser user,
            @RequestBody StartRequest request) {
        long id = sessions.start(user, request.productId(), request.batchId(), request.kind());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PostMapping("/{id}/heartbeat")
    @PreAuthorize("hasRole('OPERATOR')")
    public SessionView heartbeat(CurrentUser user, @PathVariable("id") long sessionId,
            @RequestBody HeartbeatRequest request) {
        return sessions.heartbeat(user, sessionId, request.activeSeconds());
    }

    @PostMapping("/{id}/finish")
    @PreAuthorize("hasRole('OPERATOR')")
    public SessionView finish(CurrentUser user, @PathVariable("id") long sessionId,
            @RequestBody FinishRequest request) {
        return sessions.finish(user, sessionId, request.activeSeconds(), request.outcome());
    }
}