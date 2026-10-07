package com.kiano.content.qc;

import java.nio.file.Path;

/**
 * Probes a video file for display dimensions, duration and frame rate.
 */
public interface VideoProbe {

    VideoInfo probe(Path file);
}
