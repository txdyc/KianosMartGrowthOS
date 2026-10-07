package com.kiano.content.generation;

import com.kiano.workerprotocol.ExecutorType;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net for workers that disappear without reporting an executor as
 * unavailable (laptop asleep, process killed): when no worker has reported
 * an executor available in the last 90 seconds, its QUEUED jobs move to
 * WAITING_EXECUTOR instead of being leased and timing out.
 */
@Component
public class ExecutorAvailabilityMonitor {

    private static final Duration AVAILABILITY_WINDOW = Duration.ofSeconds(90);

    private final GenerationJobStore jobs;
    private final WorkerStatusStore workerStatus;
    private final WorkerAuthProperties properties;

    public ExecutorAvailabilityMonitor(GenerationJobStore jobs, WorkerStatusStore workerStatus,
            WorkerAuthProperties properties) {
        this.jobs = jobs;
        this.workerStatus = workerStatus;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void scheduled() {
        if (properties.isMonitorEnabled()) {
            checkOnce();
        }
    }

    public void checkOnce() {
        for (ExecutorType executor : ExecutorType.values()) {
            if (workerStatus.isAvailable(executor, AVAILABILITY_WINDOW)) {
                jobs.releaseWaiting(executor);
            } else {
                jobs.markWaiting(executor);
            }
        }
    }
}
