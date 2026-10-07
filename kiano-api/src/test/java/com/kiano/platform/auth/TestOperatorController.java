package com.kiano.platform.auth;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint used by AuthControllerTest to verify role enforcement.
 */
@RestController
@RequestMapping("/api/v1/_test")
public class TestOperatorController {

    @PostMapping("/operator")
    @PreAuthorize("hasRole('OPERATOR')")
    public Map<String, Object> operator(CurrentUser user) {
        return Map.of("email", user.email(), "role", user.role().name());
    }
}
