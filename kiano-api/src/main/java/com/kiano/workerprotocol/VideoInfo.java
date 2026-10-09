package com.kiano.workerprotocol;

/**
 * Probed video properties. width and height are display dimensions, i.e.
 * already rotated for any rotation metadata; hasAudio tells whether any audio
 * stream is present (silent clips get anullsrc instead of failing). Shared
 * with the worker, which probes its own outputs.
 */
public record VideoInfo(int width, int height, double durationSeconds, double fps,
        boolean hasAudio) {
}
