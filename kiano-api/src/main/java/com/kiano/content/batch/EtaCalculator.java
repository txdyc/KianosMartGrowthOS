package com.kiano.content.batch;

import com.kiano.workerprotocol.JobStep;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Estimates the remaining time of a batch (C6 Task 4). Pure function: the
 * service resolves the remaining jobs, the step averages and the LLM averages,
 * this class only sums them. A night-only batch finishes only during the night
 * window, so its finish time spans the closed hours of every night.
 */
public final class EtaCalculator {

    /** Everything the estimate needs; averages are already resolved. */
    public record EtaInput(
            Map<JobStep, Integer> remainingGpuJobs,
            Map<JobStep, Double> avgGpuSeconds,
            int remainingFactDrafts,
            int remainingCopyRuns,
            double avgFactDraftSeconds,
            double avgCopySeconds,
            int awaitingHumanItems,
            double avgHumanSecondsPerItem,
            boolean nightOnly,
            @Nullable NightWindowSpec window,
            Instant now) {
    }

    /** The three estimates plus the GPU finish instant. */
    public record Eta(double gpuSeconds, double llmSeconds, double humanSeconds,
            Instant gpuFinishAt) {
    }

    private EtaCalculator() {
    }

    public static Eta estimate(EtaInput in) {
        double gpuSeconds = 0;
        for (Map.Entry<JobStep, Integer> entry : in.remainingGpuJobs().entrySet()) {
            gpuSeconds += entry.getValue() * in.avgGpuSeconds().getOrDefault(entry.getKey(), 0.0);
        }
        double llmSeconds = in.remainingFactDrafts() * in.avgFactDraftSeconds()
                + in.remainingCopyRuns() * in.avgCopySeconds();
        double humanSeconds = in.awaitingHumanItems() * in.avgHumanSecondsPerItem();
        return new Eta(gpuSeconds, llmSeconds, humanSeconds,
                finishAt(in.now(), gpuSeconds, in.nightOnly(), in.window()));
    }

    /**
     * A night-only batch accumulates GPU time only inside the window: closed
     * hours are skipped, so a 12 h queue started at 22:00 finishes at 01:00 of
     * the second night.
     */
    private static Instant finishAt(Instant now, double seconds, boolean nightOnly,
            @Nullable NightWindowSpec window) {
        long remaining = Math.round(seconds);
        if (!nightOnly || window == null || window.start().equals(window.end())) {
            return now.plusSeconds(remaining);
        }
        Instant cursor = now;
        int guard = 0;
        while (remaining > 0 && guard++ < 366) {
            if (NightWindow.isOpen(window.start(), window.end(),
                    LocalTime.ofInstant(cursor, window.zone()))) {
                Instant closesAt = nextBoundary(cursor, window.end(), window.zone());
                long untilClose = Duration.between(cursor, closesAt).getSeconds();
                if (remaining <= untilClose) {
                    return cursor.plusSeconds(remaining);
                }
                remaining -= untilClose;
                cursor = closesAt;
            } else {
                cursor = nextBoundary(cursor, window.start(), window.zone());
            }
        }
        return cursor;
    }

    /** First instant strictly after {@code from} whose local time is {@code time}. */
    private static Instant nextBoundary(Instant from, LocalTime time, ZoneId zone) {
        ZonedDateTime zoned = from.atZone(zone);
        LocalDateTime candidate = zoned.toLocalDate().atTime(time);
        if (!candidate.atZone(zone).toInstant().isAfter(from)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.atZone(zone).toInstant();
    }
}