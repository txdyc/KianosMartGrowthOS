package com.kiano.content.policy;

import com.kiano.content.policy.PolicyService.PolicyView;
import com.kiano.content.policy.PolicyService.SectionText;
import com.kiano.platform.auth.CurrentUser;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Store policy settings: VIEWER reads the latest version, OWNER saves a new
 * one (each save is a new version; only the latest applies).
 */
@RestController
@RequestMapping("/api/v1/content/policy")
public class PolicyController {

    private final PolicyService service;

    public PolicyController(PolicyService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public Map<String, Object> get(CurrentUser user) {
        return service.current(user.tenantId())
                .<Map<String, Object>>map(view -> Map.of(
                        "configured", true,
                        "policy", view))
                .orElseGet(() -> Map.of("configured", false));
    }

    @PutMapping
    @PreAuthorize("hasRole('OWNER')")
    public PolicyView save(CurrentUser user, @RequestBody Map<PolicySection, SectionText> sections) {
        Map<PolicySection, SectionText> normalized = new EnumMap<>(PolicySection.class);
        normalized.putAll(sections);
        return service.save(user, normalized);
    }
}