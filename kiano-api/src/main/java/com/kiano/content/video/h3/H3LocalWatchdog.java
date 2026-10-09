package com.kiano.content.video.h3;

import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cancels H3_CLIP_LOCAL jobs that waited longer than {@code local-max-wait}
 * (laptop lid closed, worker offline) and escalates each of them exactly once
 * with {@code H3_LOCAL_TIMEOUT}. Cancelling clears the lease, so a worker that
 * reports the clip afterwards is lease-lost and its result is dropped - the
 * escalation never produces two versions. A local task that fails on its own
 * reaches the coordinator through {@link
 * com.kiano.content.generation.JobFailureListener} as {@code H3_LOCAL_FAILED}.
 */
@Component
@Profile("!worker")
public class H3LocalWatchdog {

    private static final String REASON = "H3_LOCAL_TIMEOUT";

    private final GenerationJobStore jobs;
    private final H3Properties properties;
    private final ObjectProvider<H3TerminalHandler> terminalHandler;

    public H3LocalWatchdog(GenerationJobStore jobs, H3Properties properties,
            ObjectProvider<H3TerminalHandler> terminalHandler) {
        this.jobs = jobs;
        this.properties = properties;
        this.terminalHandler = terminalHandler;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void scheduled() {
        sweep();
    }

    /** One sweep; exposed for tests. */
    public void sweep() {
        Instant cutoff = Instant.now().minus(properties.getLocal().getLocalMaxWait());
        for (GenerationJob job : jobs.staleH3Local(cutoff)) {
            if (jobs.cancel(job.tenantId(), job.id(), REASON
                    + ": local H3 clip waited longer than " + properties.getLocal().getLocalMaxWait())) {
                terminalHandler.orderedStream().forEach(handler -> handler.onH3Escalate(job, REASON));
            }
        }
    }
}