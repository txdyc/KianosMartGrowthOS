package com.kiano.platform.llm.settings;

import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.llm.LlmPurpose;
import com.kiano.platform.llm.settings.LlmSettingsService.LlmSettingsView;
import com.kiano.platform.llm.settings.LlmSettingsService.ProviderInput;
import com.kiano.platform.llm.settings.LlmSettingsService.RouteInput;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OWNER-only LLM provider & route settings (spec §6). All writes are audited;
 * responses and audit entries never contain API keys.
 */
@RestController
@RequestMapping("/api/v1/settings/llm")
public class LlmSettingsController {

    private final LlmSettingsService service;
    private final ConnectionTester connectionTester;

    public LlmSettingsController(LlmSettingsService service, ConnectionTester connectionTester) {
        this.service = service;
        this.connectionTester = connectionTester;
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public LlmSettingsView get(CurrentUser user) {
        return service.get(user);
    }

    @PostMapping("/providers")
    @PreAuthorize("hasRole('OWNER')")
    public java.util.Map<String, Object> createProvider(CurrentUser user,
            @RequestBody ProviderInput input) {
        return java.util.Map.of("id", service.createProvider(user, input));
    }

    @PutMapping("/providers/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public void updateProvider(CurrentUser user, @PathVariable long id,
            @RequestBody ProviderInput input) {
        service.updateProvider(user, id, input);
    }

    @DeleteMapping("/providers/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public void deleteProvider(CurrentUser user, @PathVariable long id) {
        service.deleteProvider(user, id);
    }

    @PutMapping("/routes/{purpose}")
    @PreAuthorize("hasRole('OWNER')")
    public void saveRoute(CurrentUser user, @PathVariable String purpose,
            @RequestBody RouteInput input) {
        service.saveRoute(user, LlmPurpose.valueOf(purpose), input);
    }

    @DeleteMapping("/routes/{purpose}")
    @PreAuthorize("hasRole('OWNER')")
    public void deleteRoute(CurrentUser user, @PathVariable String purpose) {
        service.deleteRoute(user, LlmPurpose.valueOf(purpose));
    }

    @PostMapping("/routes/{purpose}/test")
    @PreAuthorize("hasRole('OWNER')")
    public ConnectionTester.TestResult test(CurrentUser user, @PathVariable String purpose) {
        return connectionTester.test(user, LlmPurpose.valueOf(purpose));
    }
}