package com.kiano.platform.auth;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint used by WorkerAuthTest to verify the worker filter chain.
 */
@RestController
@RequestMapping("/api/v1/worker")
public class TestWorkerController {

    @GetMapping("/_ping")
    public Map<String, String> ping() {
        return Map.of("pong", "worker");
    }
}
