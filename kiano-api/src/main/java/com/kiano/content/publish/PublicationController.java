package com.kiano.content.publish;

import com.kiano.commerce.PublishEnvironment;
import com.kiano.platform.auth.CurrentUser;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publish endpoints: request a publish (202 + publicationId), list the
 * product's publication history with canRollback, and rollback (Task 11).
 */
@RestController
@RequestMapping("/api/v1/content")
public class PublicationController {

    private final PublicationService service;

    public PublicationController(PublicationService service) {
        this.service = service;
    }

    @PostMapping("/products/{id}/publish")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> publish(CurrentUser user,
            @PathVariable("id") long productId,
            @RequestParam("environment") PublishEnvironment environment) {
        long publicationId = service.request(user, productId, environment);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("publicationId", publicationId));
    }

    @GetMapping("/products/{id}/publications")
    @PreAuthorize("hasRole('VIEWER')")
    public List<PublicationView> publications(CurrentUser user,
            @PathVariable("id") long productId) {
        return service.list(user, productId);
    }

    @PostMapping("/publications/{id}/rollback")
    @PreAuthorize("hasRole('OPERATOR')")
    public PublicationView rollback(CurrentUser user, @PathVariable("id") long publicationId,
            @RequestParam(defaultValue = "false") boolean force) {
        return service.rollback(user, publicationId, force);
    }
}