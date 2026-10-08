package com.kiano.content.template;

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
 * Registers the baseline PAGE_INFO (INFOGRAPHIC) and PAGE_SPEC (SPEC)
 * templates from the classpath as APPROVED v1 on first startup per tenant.
 * A stored v1 whose body differs from the classpath fails startup: published
 * template versions are immutable, so changes must ship as v2.
 */
@Component
public class TemplateBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TemplateBootstrap.class);

    private record Baseline(String code, String kind) {
    }

    private static final List<Baseline> BASELINES = List.of(
            new Baseline("PAGE_INFO", "INFOGRAPHIC"),
            new Baseline("PAGE_SPEC", "SPEC"),
            new Baseline("FACT_PROMPT", "FACT_PROMPT"),
            new Baseline("POLICY_BLOCK", "POLICY_BLOCK"),
            new Baseline("COPY_PROMPT", "COPY_PROMPT"),
            new Baseline("COPY_LONG", "COPY_LAYOUT"),
            new Baseline("COPY_SHORT", "COPY_LAYOUT"),
            new Baseline("AD_COPY_PROMPT", "COPY_PROMPT"));

    private final JdbcTemplate jdbcTemplate;
    private final TemplateRegistry registry;

    public TemplateBootstrap(JdbcTemplate jdbcTemplate, TemplateRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapAll();
    }

    public void bootstrapAll() {
        for (Long tenantId : jdbcTemplate.queryForList("select id from tenant", Long.class)) {
            for (Baseline baseline : BASELINES) {
                bootstrapCode(tenantId, baseline);
            }
        }
    }

    private void bootstrapCode(long tenantId, Baseline baseline) {
        // HTML templates use v1.html; prompt templates (FACT_PROMPT) use v1.txt.
        String body = readClasspath("templates/content/" + baseline.code() + "/v1.html");
        if (body == null) {
            body = readClasspath("templates/content/" + baseline.code() + "/v1.txt");
        }
        if (body == null) {
            throw new IllegalStateException("Baseline template " + baseline.code()
                    + " v1 is missing from the classpath");
        }
        TemplateEntity existing = registry.find(tenantId, baseline.code(), 1);
        if (existing == null) {
            registry.insertApproved(tenantId, baseline.code(), baseline.kind(), 1, body);
            log.info("Bootstrapped baseline template {} v1 for tenant {}",
                    baseline.code(), tenantId);
        } else if (!body.equals(existing.getBody())) {
            throw new IllegalStateException("Baseline template " + baseline.code()
                    + " v1 body differs from the stored version; published template"
                    + " versions are immutable - ship v2 instead");
        }
    }

    private @Nullable String readClasspath(String path) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read " + path + " from the classpath", ex);
        }
    }
}
