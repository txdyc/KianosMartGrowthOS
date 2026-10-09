package com.kiano.content.video;

import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.Edl;
import com.kiano.workerprotocol.Edl.Audio;
import com.kiano.workerprotocol.Edl.Kind;
import com.kiano.workerprotocol.Edl.Overlay;
import com.kiano.workerprotocol.Edl.Segment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * Pure EDL planner (C5 T4): turns a video type, the chosen footage windows and
 * the caption count into the full EDL - hook (H3 clip or real footage), body
 * trimmed to {@code min(bodyMax, window)} but never below {@code bodyMin}
 * (409 VIDEO_SOURCE_TOO_SHORT otherwise), captions and the end card - with the
 * caption overlays placed on the output timeline. The result is validated and
 * a 422 with the first violation is thrown when the gate rejects it.
 */
public final class VideoPlanner {

    /** Sources keyed by the job input key (v1|v2|v3); windows picked in Task 5. */
    public record PlanInput(VideoType type, Map<String, SourceClip> sources, boolean useH3,
            Windows windows, int captionCount, @Nullable String musicInput) {
    }

    public record SourceClip(long sourceMediaId, double duration, int width, int height,
            boolean hasAudio) {
    }

    public record Window(double in, double out) {
    }

    public record Windows(Window hook, Window body, Window captions) {
    }

    private VideoPlanner() {
    }

    /** demo/problem open with V1, unboxing with V3; captions always use V2. */
    private static String hookSource(VideoType type) {
        return type == VideoType.UNBOXING ? "v3" : "v1";
    }

    private static String bodySource(VideoType type) {
        return type == VideoType.UNBOXING ? "v3" : "v1";
    }

    public static Edl plan(PlanInput in, VideoProperties props) {
        VideoType type = in.type();
        VideoProperties.Recipe recipe = props.recipe(type);
        double hookSeconds = props.getHookSeconds();
        double captionsSeconds = props.getCaptionsSeconds();
        double endCardSeconds = props.getEndCardSeconds();
        String hookSource = hookSource(type);
        String bodySource = bodySource(type);
        String captionsSource = "v2";

        double bodyWindowLength = in.windows().body().out() - in.windows().body().in();
        double bodySeconds = Math.min(recipe.getBodyMaxSeconds(), bodyWindowLength);
        if (bodySeconds < recipe.getBodyMinSeconds()) {
            throw new ApiException(HttpStatus.CONFLICT, "VIDEO_SOURCE_TOO_SHORT",
                    "The " + bodySource + " footage is shorter than the " + type.wire()
                            + " body needs",
                    Map.of("shot", bodySource, "neededSeconds", recipe.getBodyMinSeconds(),
                            "actualSeconds", bodyWindowLength));
        }

        Map<String, Double> inputDurations = new LinkedHashMap<>();
        for (Map.Entry<String, SourceClip> entry : in.sources().entrySet()) {
            inputDurations.put(entry.getKey(), entry.getValue().duration());
        }

        List<Segment> segments = new ArrayList<>();
        if (in.useH3()) {
            segments.add(new Segment(Kind.H3, "hook", "h3_hook", 0, hookSeconds, false, false, null));
            inputDurations.put("h3_hook", hookSeconds);
        } else {
            SourceClip src = requireSource(in, hookSource);
            Window window = in.windows().hook();
            segments.add(new Segment(Kind.REAL, "hook", hookSource, window.in(),
                    window.in() + hookSeconds, src.hasAudio(), blurFill(src, props),
                    src.sourceMediaId()));
        }
        SourceClip bodySrc = requireSource(in, bodySource);
        Window bodyWindow = in.windows().body();
        segments.add(new Segment(Kind.REAL, "body", bodySource, bodyWindow.in(),
                bodyWindow.in() + bodySeconds, bodySrc.hasAudio(), blurFill(bodySrc, props),
                bodySrc.sourceMediaId()));
        SourceClip captionsSrc = requireSource(in, captionsSource);
        Window captionsWindow = in.windows().captions();
        segments.add(new Segment(Kind.REAL, "captions", captionsSource, captionsWindow.in(),
                captionsWindow.in() + captionsSeconds, captionsSrc.hasAudio(),
                blurFill(captionsSrc, props), captionsSrc.sourceMediaId()));
        segments.add(new Segment(Kind.STILL, "endcard", "endcard", 0, endCardSeconds, false,
                false, null));
        inputDurations.put("endcard", endCardSeconds);

        double captionsStart = hookSeconds + bodySeconds;
        List<Overlay> overlays = new ArrayList<>();
        overlays.add(new Overlay("cap_hook", 0, hookSeconds));
        overlays.add(new Overlay("cap_0", hookSeconds, captionsStart));
        int captionCount = Math.max(1, in.captionCount());
        double slot = captionsSeconds / captionCount;
        for (int i = 1; i <= captionCount; i++) {
            overlays.add(new Overlay("cap_" + i, captionsStart + (i - 1) * slot,
                    captionsStart + i * slot));
        }

        Edl edl = new Edl(type.wire(), 1080, 1920, segments, overlays, in.musicInput(),
                new Audio(props.getOriginalVolume(), props.getMusicVolume(),
                        props.getMusicFadeOut()));
        List<EdlValidator.Violation> violations =
                EdlValidator.validate(edl, props, inputDurations);
        if (!violations.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, violations.get(0).code(),
                    "EDL_INVALID: " + violations.get(0).code(),
                    Map.of("violations", violations.stream()
                            .map(v -> Map.of("code", v.code(), "details", v.details())).toList()));
        }
        return edl;
    }

    /** Aspect within 9:16 ± tolerance counts as portrait; everything else blurs the fill. */
    private static boolean blurFill(SourceClip src, VideoProperties props) {
        if (src.width() <= 0 || src.height() <= 0) {
            return false;
        }
        double aspect = (double) src.width() / src.height();
        double target = 9.0 / 16.0;
        double tolerance = target * props.getPortraitTolerance();
        return Math.abs(aspect - target) > tolerance;
    }

    private static SourceClip requireSource(PlanInput in, String key) {
        SourceClip src = in.sources().get(key);
        if (src == null) {
            throw new IllegalArgumentException("Source " + key + " missing from PlanInput");
        }
        return src;
    }
}
