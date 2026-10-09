package com.kiano.workerprotocol;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The edit decision list shared between the api (planning) and the worker
 * (composition): one segment per footage/still input with in/out points, the
 * overlay PNGs placed on the output timeline, and the audio mix recipe.
 * Segment {@code input} and overlay {@code input} are keys of the job's
 * {@code inputs} map (e.g. {@code v1}, {@code h3_hook}, {@code cap_0},
 * {@code endcard}, {@code music}).
 */
public record Edl(String videoType, double width, double height, List<Segment> segments,
        List<Overlay> overlays, @Nullable String musicInput, Audio audio) {

    public enum Kind {
        /** Real footage; in/out are source timecodes. */
        REAL,
        /** An H3 clip (API or local); counts toward the AI ratio. */
        H3,
        /** A still image (the end card); in = 0, out = its duration. */
        STILL
    }

    public record Segment(Kind kind, String role /* hook|body|captions|endcard */, String input,
            double in, double out, boolean hasAudio, boolean blurFill,
            @Nullable Long sourceMediaId) {
    }

    /** Times are on the composed output timeline. */
    public record Overlay(String input, double start, double end) {
    }

    public record Audio(double originalVolume, double musicVolume, double musicFadeOut) {
    }
}
