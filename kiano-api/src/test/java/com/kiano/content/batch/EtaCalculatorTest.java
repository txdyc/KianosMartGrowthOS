package com.kiano.content.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.workerprotocol.JobStep;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * EtaCalculator (C6 Task 4): the estimate is the sum of the remaining jobs
 * times their averages, and a night-only batch only accumulates GPU time inside
 * the window - a 12 h queue started at 22:00 finishes at 01:00 of the second
 * night.
 */
class EtaCalculatorTest {

    @Test
    void eta_sumsRemainingJobsTimesStepAverages() {
        EtaCalculator.EtaInput in = new EtaCalculator.EtaInput(
                Map.of(JobStep.CUTOUT, 2, JobStep.WHITE_MAIN, 1, JobStep.SCENE, 3),
                Map.of(JobStep.CUTOUT, 30.0, JobStep.WHITE_MAIN, 20.0, JobStep.SCENE, 60.0),
                2, 1, 25.0, 10.0, 3, 60.0, false, null,
                Instant.parse("2026-10-10T22:00:00Z"));

        EtaCalculator.Eta eta = EtaCalculator.estimate(in);

        assertThat(eta.gpuSeconds()).isEqualTo(260.0);
        assertThat(eta.llmSeconds()).isEqualTo(60.0);
        assertThat(eta.humanSeconds()).isEqualTo(180.0);
        assertThat(eta.gpuFinishAt()).isEqualTo(Instant.parse("2026-10-10T22:04:20Z"));
    }

    @Test
    void eta_nightOnly_finishSpansWindowsAcrossNights() {
        // 12 h of GPU work, 9 h per night: 22:00 -> 07:00, then 22:00 -> 01:00
        EtaCalculator.EtaInput in = new EtaCalculator.EtaInput(
                Map.of(JobStep.SCENE, 12), Map.of(JobStep.SCENE, 3600.0),
                0, 0, 0.0, 0.0, 0, 0.0, true, accraNight(),
                Instant.parse("2026-10-10T22:00:00Z"));

        EtaCalculator.Eta eta = EtaCalculator.estimate(in);

        assertThat(eta.gpuSeconds()).isEqualTo(43200.0);
        assertThat(eta.gpuFinishAt()).isEqualTo(Instant.parse("2026-10-12T01:00:00Z"));
    }

    @Test
    void eta_nightOnly_startedOutsideTheWindow_waitsForTheNextOne() {
        // 2 h of work started at 12:00: the window opens at 22:00
        EtaCalculator.EtaInput in = new EtaCalculator.EtaInput(
                Map.of(JobStep.SCENE, 2), Map.of(JobStep.SCENE, 3600.0),
                0, 0, 0.0, 0.0, 0, 0.0, true, accraNight(),
                Instant.parse("2026-10-10T12:00:00Z"));

        EtaCalculator.Eta eta = EtaCalculator.estimate(in);

        assertThat(eta.gpuFinishAt()).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));
    }

    private static NightWindowSpec accraNight() {
        return new NightWindowSpec(LocalTime.of(22, 0), LocalTime.of(7, 0),
                ZoneId.of("Africa/Accra"));
    }
}