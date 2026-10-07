package com.kiano.content.workflow;

import com.kiano.platform.auth.CurrentUser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * OWNER-only workflow management: list versions, upload a new version
 * (multipart fields workflow + manifest) and activate a version.
 */
@RestController
@RequestMapping("/api/v1/content/workflows")
public class WorkflowController {

    private final WorkflowRegistry registry;

    public WorkflowController(WorkflowRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public List<WorkflowRegistry.ComfyWorkflowView> list(CurrentUser user) {
        return registry.list(user.tenantId());
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<WorkflowRegistry.ComfyWorkflowView> register(CurrentUser user,
            @RequestParam("workflow") MultipartFile workflow,
            @RequestParam("manifest") MultipartFile manifest) throws IOException {
        WorkflowRegistry.ComfyWorkflowView view = registry.register(user.tenantId(),
                new String(workflow.getBytes(), StandardCharsets.UTF_8),
                new String(manifest.getBytes(), StandardCharsets.UTF_8),
                user.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('OWNER')")
    public WorkflowRegistry.ComfyWorkflowView activate(CurrentUser user, @PathVariable long id) {
        return registry.activate(user.tenantId(), id, user.userId());
    }
}
