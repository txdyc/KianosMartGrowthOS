package com.kiano.content.video.h3;

import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.workflow.WorkflowRegistry;
import com.kiano.workerprotocol.ExecutorType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Picks the H3 backend for a clip from {@code kiano.content.video.h3.backend}
 * (C5 Global Constraints #5): LOCAL_FIRST prefers an active {@code H3_I2V}
 * workflow and falls back to an enabled API, LOCAL_ONLY / API_ONLY narrow that
 * and OFF disables H3 entirely. A local clip that timed out or failed may
 * escalate to the API, but only in LOCAL_FIRST, only from LOCAL and only while
 * the daily budget still has room - anything else falls back to real footage.
 */
@Component
public class H3BackendSelector {

    private final WorkflowRegistry workflows;
    private final H3Properties properties;
    private final GenerationJobStore jobs;

    public H3BackendSelector(WorkflowRegistry workflows, H3Properties properties,
            GenerationJobStore jobs) {
        this.workflows = workflows;
        this.properties = properties;
        this.jobs = jobs;
    }

    /** Backend for a new H3 clip; empty means the hook uses real footage. */
    public Optional<H3Backend> initial(long tenantId) {
        return switch (properties.getBackend()) {
            case LOCAL_FIRST -> local(tenantId).or(this::api);
            case LOCAL_ONLY -> local(tenantId);
            case API_ONLY -> api();
            case OFF -> Optional.empty();
        };
    }

    /** Backend to escalate a failed clip to; empty means real footage instead. */
    public Optional<H3Backend> next(long tenantId, H3Backend failed) {
        if (properties.getBackend() != H3Properties.Mode.LOCAL_FIRST || failed != H3Backend.LOCAL) {
            return Optional.empty();
        }
        return withinBudget(tenantId) ? Optional.of(H3Backend.API) : Optional.empty();
    }

    private Optional<H3Backend> local(long tenantId) {
        return workflows.active(tenantId, "H3_I2V").isPresent()
                ? Optional.of(H3Backend.LOCAL)
                : Optional.empty();
    }

    private Optional<H3Backend> api() {
        return properties.getApi().isEnabled() ? Optional.of(H3Backend.API) : Optional.empty();
    }

    private boolean withinBudget(long tenantId) {
        if (!properties.getApi().isEnabled()) {
            return false;
        }
        BigDecimal next = properties.getApi().getPricePerSecondUsd()
                .multiply(BigDecimal.valueOf(properties.getRequestSeconds()));
        // clips already submitted but not finished are billed too (cost lands on completion)
        BigDecimal committed = jobs.costSince(tenantId, ExecutorType.H3, startOfUtcDay())
                .add(next.multiply(BigDecimal.valueOf(jobs.inFlightExternal(tenantId,
                        ExecutorType.H3))));
        return committed.add(next).compareTo(properties.getApi().getDailyBudgetUsd()) <= 0;
    }

    private static Instant startOfUtcDay() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}