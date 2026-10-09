package com.kiano.content.video;

import com.kiano.workerprotocol.Edl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure validation of an EDL against the recipe (Task 4 / Review Focus 4): the
 * AI ratio must stay under {@code maxAiRatio}, the total inside [min, max],
 * every segment inside its source, the body inside the type's recipe window,
 * overlays on the timeline and the roles in hook → body → captions → endcard
 * order, each exactly once. The planner runs it before saving; manual edits
 * are re-validated so a trimmed body cannot push the AI ratio over the gate.
 */
public final class EdlValidator {

    public record Violation(String code, Map<String, Object> details) {
    }

    private EdlValidator() {
    }

    public static List<Violation> validate(Edl edl, VideoProperties props,
            Map<String, Double> inputDurations) {
        List<Violation> violations = new ArrayList<>();
        double total = EdlMath.totalSeconds(edl);

        if (EdlMath.aiRatio(edl) > props.getMaxAiRatio()) {
            violations.add(violation("AI_RATIO_EXCEEDED", Map.of(
                    "aiRatio", EdlMath.aiRatio(edl),
                    "maxAiRatio", props.getMaxAiRatio())));
        }
        if (total < props.getMinTotal() || total > props.getMaxTotal()) {
            violations.add(violation("TOTAL_OUT_OF_RANGE", Map.of(
                    "totalSeconds", total,
                    "minTotal", props.getMinTotal(),
                    "maxTotal", props.getMaxTotal())));
        }
        for (Edl.Segment segment : edl.segments()) {
            Double sourceSeconds = inputDurations == null ? null
                    : inputDurations.get(segment.input());
            if (segment.in() < 0 || segment.in() >= segment.out()
                    || (sourceSeconds != null && segment.out() > sourceSeconds)) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("input", segment.input());
                details.put("role", segment.role());
                details.put("in", segment.in());
                details.put("out", segment.out());
                if (sourceSeconds != null) {
                    details.put("sourceSeconds", sourceSeconds);
                }
                violations.add(violation("SEGMENT_OUT_OF_SOURCE", details));
            }
            if ("body".equals(segment.role())) {
                VideoProperties.Recipe recipe = props.recipe(VideoType.fromWire(edl.videoType()));
                double length = segment.out() - segment.in();
                if (length < recipe.getBodyMinSeconds() || length > recipe.getBodyMaxSeconds()) {
                    violations.add(violation("BODY_OUT_OF_RANGE", Map.of(
                            "bodySeconds", length,
                            "bodyMin", recipe.getBodyMinSeconds(),
                            "bodyMax", recipe.getBodyMaxSeconds())));
                }
            }
        }
        for (Edl.Overlay overlay : edl.overlays()) {
            if (overlay.start() < 0 || overlay.start() >= overlay.end() || overlay.end() > total) {
                violations.add(violation("OVERLAY_OUT_OF_TIMELINE", Map.of(
                        "input", overlay.input(),
                        "start", overlay.start(),
                        "end", overlay.end(),
                        "totalSeconds", total)));
            }
        }
        List<String> roles = edl.segments().stream().map(Edl.Segment::role).toList();
        List<String> expected = List.of("hook", "body", "captions", "endcard");
        if (!roles.equals(expected)) {
            violations.add(violation("SEGMENT_ORDER", Map.of(
                    "actual", roles, "expected", expected)));
        }
        return List.copyOf(violations);
    }

    private static Violation violation(String code, Map<String, Object> details) {
        return new Violation(code, details);
    }
}
