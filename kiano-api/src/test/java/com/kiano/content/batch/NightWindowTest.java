package com.kiano.content.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * The optional night window (C6 Task 1): batch GPU jobs with {@code night_only}
 * are only leased inside it. The window may wrap midnight; equal bounds mean
 * the window is always open.
 */
class NightWindowTest {

    @Test
    void wrapsMidnight_22to07() {
        LocalTime start = LocalTime.of(22, 0);
        LocalTime end = LocalTime.of(7, 0);

        assertThat(NightWindow.isOpen(start, end, LocalTime.of(23, 30))).isTrue();
        assertThat(NightWindow.isOpen(start, end, LocalTime.of(6, 59))).isTrue();
        assertThat(NightWindow.isOpen(start, end, LocalTime.of(22, 0))).isTrue();
        assertThat(NightWindow.isOpen(start, end, LocalTime.of(7, 0))).isFalse();
        assertThat(NightWindow.isOpen(start, end, LocalTime.of(21, 59))).isFalse();
    }

    @Test
    void sameDayWindow_and_equalBoundsMeansAlwaysOpen() {
        LocalTime nine = LocalTime.of(9, 0);
        LocalTime five = LocalTime.of(17, 0);

        assertThat(NightWindow.isOpen(nine, five, LocalTime.of(12, 0))).isTrue();
        assertThat(NightWindow.isOpen(nine, five, LocalTime.of(8, 59))).isFalse();
        assertThat(NightWindow.isOpen(nine, five, LocalTime.of(17, 0))).isFalse();
        assertThat(NightWindow.isOpen(nine, nine, LocalTime.of(3, 0))).isTrue();
    }

    @Test
    void batchProperties_convertsToTheConfiguredZone() {
        BatchProperties properties = new BatchProperties();
        properties.setZone("Africa/Accra"); // UTC+0, no daylight saving
        properties.setNightWindow("22:00-07:00");

        assertThat(properties.nightOpen(Instant.parse("2026-10-09T23:30:00Z"))).isTrue();
        assertThat(properties.nightOpen(Instant.parse("2026-10-09T06:00:00Z"))).isTrue();
        assertThat(properties.nightOpen(Instant.parse("2026-10-09T12:00:00Z"))).isFalse();

        properties.setZone("Asia/Shanghai"); // UTC+8: 23:30Z is 07:30 local
        assertThat(properties.nightOpen(Instant.parse("2026-10-09T23:30:00Z"))).isFalse();
        assertThat(properties.nightOpen(Instant.parse("2026-10-09T16:00:00Z"))).isTrue(); // 00:00 local
    }
}