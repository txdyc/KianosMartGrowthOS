package com.kiano.content.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.workerprotocol.Edl;
import com.kiano.workerprotocol.Edl.Audio;
import com.kiano.workerprotocol.Edl.Kind;
import com.kiano.workerprotocol.Edl.Overlay;
import com.kiano.workerprotocol.Edl.Segment;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * EdlValidator (C5 T4): the AI-ratio gate for manually trimmed EDLs, segment
 * order/count, sources shorter than the cut and overlays outside the timeline.
 */
class EdlValidatorTest {

    private final VideoProperties props = new VideoProperties();

    private static Edl edl(List<Segment> segments, List<Overlay> overlays) {
        return new Edl("demo", 1080, 1920, segments, overlays, "music",
                new Audio(0.5, 0.35, 1.5));
    }

    private static Segment seg(Kind kind, String role, String input, double in, double out) {
        return new Segment(kind, role, input, in, out, true, false, null);
    }

    private static Map<String, Double> durations() {
        return Map.of("v1", 10.0, "v2", 10.0, "h3_hook", 3.0, "endcard", 4.0);
    }

    @Test
    void validate_manualTrimMakesAiOver30_flagged() {
        // H3 hook 3s with the real body trimmed to 2s: 3 / (3+2) = 0.6
        Edl edl = edl(List.of(
                seg(Kind.H3, "hook", "h3_hook", 0, 3),
                seg(Kind.REAL, "body", "v1", 0, 2)), List.of());

        List<EdlValidator.Violation> violations =
                EdlValidator.validate(edl, props, durations());

        assertThat(violations).extracting(EdlValidator.Violation::code)
                .contains("AI_RATIO_EXCEEDED", "BODY_OUT_OF_RANGE", "TOTAL_OUT_OF_RANGE",
                        "SEGMENT_ORDER");
    }

    @Test
    void validate_outBeyondSource_inGeOut_orderAndCounts() {
        Edl edl = edl(List.of(
                seg(Kind.REAL, "hook", "v1", 0, 3),
                seg(Kind.REAL, "body", "v1", 0, 12),           // beyond the 10s source
                seg(Kind.REAL, "captions", "v2", 8, 7),        // in >= out
                seg(Kind.STILL, "endcard", "endcard", 0, 4),
                seg(Kind.STILL, "endcard", "endcard", 0, 4)), // duplicate role
                List.of(new Overlay("cap_hook", 0, 3), new Overlay("late", 25, 30)));

        List<EdlValidator.Violation> violations =
                EdlValidator.validate(edl, props, durations());

        assertThat(violations).extracting(EdlValidator.Violation::code)
                .contains("SEGMENT_OUT_OF_SOURCE", "SEGMENT_ORDER", "OVERLAY_OUT_OF_TIMELINE");
        EdlValidator.Violation out = violations.stream()
                .filter(v -> v.code().equals("SEGMENT_OUT_OF_SOURCE")).findFirst().get();
        assertThat(out.details()).containsEntry("input", "v1");
        assertThat(out.details()).containsKey("sourceSeconds");
        assertThat(out.details()).containsKey("out");
    }

    @Test
    void validate_validDemoEdl_noViolations() {
        Edl edl = edl(List.of(
                seg(Kind.REAL, "hook", "v1", 0, 3),
                seg(Kind.REAL, "body", "v1", 1, 9),
                seg(Kind.REAL, "captions", "v2", 2, 7),
                seg(Kind.STILL, "endcard", "endcard", 0, 4)),
                List.of(new Overlay("cap_hook", 0, 3), new Overlay("cap_0", 3, 11)));

        assertThat(EdlValidator.validate(edl, props, durations())).isEmpty();
    }
}
