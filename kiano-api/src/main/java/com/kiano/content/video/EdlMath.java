package com.kiano.content.video;

import com.kiano.workerprotocol.Edl;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure arithmetic over an EDL: total duration, AI seconds (only H3 segments),
 * the 3-decimal AI ratio and whether the cut contains any AI footage.
 */
public final class EdlMath {

    private EdlMath() {
    }

    public static double totalSeconds(Edl edl) {
        double total = 0;
        for (Edl.Segment segment : edl.segments()) {
            total += segment.out() - segment.in();
        }
        return total;
    }

    public static double aiSeconds(Edl edl) {
        double ai = 0;
        for (Edl.Segment segment : edl.segments()) {
            if (segment.kind() == Edl.Kind.H3) {
                ai += segment.out() - segment.in();
            }
        }
        return ai;
    }

    /** AI seconds ÷ total seconds, rounded to 3 decimals. */
    public static double aiRatio(Edl edl) {
        double total = totalSeconds(edl);
        if (total <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(aiSeconds(edl) / total).setScale(3, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public static boolean mixed(Edl edl) {
        return edl.segments().stream().anyMatch(s -> s.kind() == Edl.Kind.H3);
    }
}
