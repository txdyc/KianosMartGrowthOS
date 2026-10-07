package com.kiano.content.generation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Requeues generation jobs whose worker lease expired without a final
 * report (worker crashed, laptop lid closed). Runs every 30 seconds;
 * disabled in tests via kiano.worker.monitor-enabled.
 */
@Component
public class LeaseReaper {

    private final GenerationJobStore jobs;
    private final WorkerAuthProperties properties;

    public LeaseReaper(GenerationJobStore jobs, WorkerAuthProperties properties) {
        this.jobs = jobs;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void scheduled() {
        if (properties.isMonitorEnabled()) {
            reapOnce();
        }
    }

    public void reapOnce() {
        jobs.reapExpiredLeases();
    }
}
