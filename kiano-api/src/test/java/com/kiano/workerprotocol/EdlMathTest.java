package com.kiano.workerprotocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.workerprotocol.Edl.Audio;
import com.kiano.workerprotocol.Edl.Kind;
import com.kiano.workerprotocol.Edl.Segment;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * EdlMath (C5 T4): total duration, AI seconds that only count H3 segments,
 * the 3-decimal AI ratio and the mixed flag.
 */
class EdlMathTest {

    private static Edl edl(Segment... segments) {
        return new Edl("demo", 1080, 1920, List.of(segments), List.of(), "music",
                new Audio(0.5, 0.35, 1.5));
    }

    private static Segment seg(Kind kind, String role, double in, double out) {
        return new Segment(kind, role, "in", in, out, false, false, null);
    }

    @Test
    void aiRatio_onlyCountsH3_endCardIsNonAi() {
        Edl edl = edl(
                seg(Kind.H3, "hook", 0, 3),
                seg(Kind.REAL, "body", 0, 12),
                seg(Kind.REAL, "captions", 0, 5),
                seg(Kind.STILL, "endcard", 0, 4));

        assertThat(EdlMath.totalSeconds(edl)).isEqualTo(24.0);
        assertThat(EdlMath.aiSeconds(edl)).isEqualTo(3.0);
        assertThat(EdlMath.aiRatio(edl)).isEqualTo(0.125);
        assertThat(EdlMath.mixed(edl)).isTrue();
    }

    @Test
    void realOnly_notMixed_ratioZero() {
        Edl edl = edl(
                seg(Kind.REAL, "hook", 0, 3),
                seg(Kind.REAL, "body", 0, 8),
                seg(Kind.REAL, "captions", 0, 5),
                seg(Kind.STILL, "endcard", 0, 4));

        assertThat(EdlMath.aiSeconds(edl)).isZero();
        assertThat(EdlMath.aiRatio(edl)).isEqualTo(0.0);
        assertThat(EdlMath.mixed(edl)).isFalse();
    }
}
