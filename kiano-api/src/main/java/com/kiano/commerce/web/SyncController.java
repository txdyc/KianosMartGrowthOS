package com.kiano.commerce.web;

import com.kiano.commerce.sync.ProductSyncScheduler;
import com.kiano.commerce.sync.ProductSyncTaskHandler;
import com.kiano.commerce.woo.CommercePortFactory;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.queue.TaskQueue;
import com.kiano.platform.queue.TaskView;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual sync trigger (OPERATOR) and latest-run status (VIEWER). Triggering
 * with no configured integration returns 409 WOO_NOT_CONFIGURED; a queued or
 * running sync dedupes to {alreadyQueued: true}.
 */
@RestController
@RequestMapping("/api/v1/commerce/sync")
public class SyncController {

    private final CommercePortFactory portFactory;
    private final TaskQueue taskQueue;

    public SyncController(CommercePortFactory portFactory, TaskQueue taskQueue) {
        this.portFactory = portFactory;
        this.taskQueue = taskQueue;
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> triggerSync(CurrentUser user) {
        portFactory.forTenant(user.tenantId());
        Optional<Long> taskId = taskQueue.enqueue(user.tenantId(), ProductSyncTaskHandler.TYPE,
                Map.of(), ProductSyncScheduler.DEDUPE_KEY);
        if (taskId.isEmpty()) {
            return ResponseEntity.accepted().body(Map.of("alreadyQueued", true));
        }
        return ResponseEntity.accepted().body(Map.of("taskId", taskId.get()));
    }

    @GetMapping("/latest")
    @PreAuthorize("hasRole('VIEWER')")
    public ResponseEntity<TaskView> latest(CurrentUser user) {
        return taskQueue.latest(user.tenantId(), ProductSyncTaskHandler.TYPE)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
