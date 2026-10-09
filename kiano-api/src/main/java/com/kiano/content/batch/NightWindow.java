package com.kiano.content.batch;

import java.time.LocalTime;

/**
 * The optional batch night window (C6 Task 1): batch GPU jobs flagged
 * {@code night_only} are only leased inside it, so a batch can be left to run
 * overnight. The window may wrap midnight (22:00-07:00); equal bounds mean the
 * window is always open.
 */
public final class NightWindow {

    private NightWindow() {
    }

    /** Whether {@code now} falls inside the window [start, end). */
    public static boolean isOpen(LocalTime start, LocalTime end, LocalTime now) {
        if (start.equals(end)) {
            return true;
        }
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }
}