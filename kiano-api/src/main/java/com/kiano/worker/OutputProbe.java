package com.kiano.worker;

import java.nio.file.Path;

/**
 * Measures a produced output file (currently used for .mp4 videos so the
 * worker can report duration and display size alongside the sha256).
 */
public interface OutputProbe {

    VideoOutput probe(Path file);

    record VideoOutput(double durationSeconds, int width, int height) {
    }
}
