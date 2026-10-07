package com.kiano.content.qc;

/**
 * Probed video properties. width and height are display dimensions, i.e.
 * already rotated for any rotation metadata.
 */
public record VideoInfo(int width, int height, double durationSeconds, double fps) {
}
