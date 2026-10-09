package com.kiano.content.batch;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * The batch night window as a value (C6 Task 4): "22:00-07:00" in one zone.
 * The ETA needs it to span a long GPU queue across several nights.
 */
public record NightWindowSpec(LocalTime start, LocalTime end, ZoneId zone) {
}