package com.kiano.content.workflow;

import com.kiano.platform.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Registers the baseline CUTOUT and SCENE workflows from the classpath
 * (comfy/{code}/v1/) on first startup per tenant. Missing files only WARN:
 * the baseline workflow files are shipped with Task 12.
 */
@Component
public class WorkflowBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowBootstrap.class);
    private static final List<String> BASELINE_CODES = List.of("CUTOUT", "SCENE");

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowRegistry registry;

    public WorkflowBootstrap(JdbcTemplate jdbcTemplate, WorkflowRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapAll();
    }

    public void bootstrapAll() {
        for (Long tenantId : jdbcTemplate.queryForList("select id from tenant", Long.class)) {
            for (String code : BASELINE_CODES) {
                bootstrapCode(tenantId, code);
            }
        }
    }

    private void bootstrapCode(long tenantId, String code) {
        if (registry.exists(tenantId, code)) {
            return;
        }
        String workflow = readClasspath("comfy/" + code + "/v1/workflow.json");
        String manifest = readClasspath("comfy/" + code + "/v1/manifest.json");
        if (workflow == null || manifest == null) {
            log.warn("Baseline workflow comfy/{}/v1 not found on the classpath; skipping", code);
            return;
        }
        try {
            WorkflowRegistry.ComfyWorkflowView view = registry.register(tenantId, workflow, manifest, null);
            registry.activate(tenantId, view.id(), null);
            log.info("Bootstrapped baseline workflow {} v{} for tenant {}", code, view.version(), tenantId);
        } catch (ApiException ex) {
            log.error("Baseline workflow comfy/{}/v1 failed validation: {}", code, ex.getDetails());
        }
    }

    private @Nullable String readClasspath(String path) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("Failed to read {} from the classpath", path, ex);
            return null;
        }
    }
}
