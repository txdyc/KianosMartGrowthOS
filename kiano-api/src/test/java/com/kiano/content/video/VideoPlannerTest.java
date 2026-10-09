package com.kiano.content.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.kiano.content.video.VideoPlanner.PlanInput;
import com.kiano.content.video.VideoPlanner.SourceClip;
import com.kiano.content.video.VideoPlanner.Window;
import com.kiano.content.video.VideoPlanner.Windows;
import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.Edl;
import com.kiano.workerprotocol.Edl.Kind;
import com.kiano.workerprotocol.EdlMath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * VideoPlanner (C5 T4): builds the EDL for a video type - hook (H3 or real
 * footage), body trimmed to the recipe, captions and the end card, with the
 * caption overlay slots on the output timeline. Short body sources give 409.
 */
class VideoPlannerTest {

    private final VideoProperties props = new VideoProperties();

    private static SourceClip portrait(double duration) {
        return new SourceClip(42L, duration, 1080, 1920, true);
    }

    private static SourceClip landscape(double duration) {
        return new SourceClip(43L, duration, 1920, 1080, true);
    }

    private static PlanInput demo(Map<String, SourceClip> sources, boolean useH3,
            Window body, int captionCount) {
        return new PlanInput(VideoType.DEMO, sources, useH3,
                new Windows(new Window(0, 3), body, new Window(2, 7)), captionCount, "music");
    }

    @Test
    void plan_demoWithH3_structureAndTimings() {
        PlanInput in = demo(Map.of(
                "v1", portrait(20), "v2", portrait(20), "v3", portrait(20)), true,
                new Window(1, 9), 3);

        Edl edl = VideoPlanner.plan(in, props);

        assertThat(edl.videoType()).isEqualTo("demo");
        assertThat(edl.width()).isEqualTo(1080);
        assertThat(edl.height()).isEqualTo(1920);
        assertThat(edl.musicInput()).isEqualTo("music");
        // segment order and timing
        assertThat(edl.segments()).extracting(Edl.Segment::role)
                .containsExactly("hook", "body", "captions", "endcard");
        Edl.Segment hook = edl.segments().get(0);
        assertThat(hook.kind()).isEqualTo(Kind.H3);
        assertThat(hook.input()).isEqualTo("h3_hook");
        assertThat(hook.in()).isZero();
        assertThat(hook.out()).isEqualTo(3.0);
        assertThat(hook.hasAudio()).isFalse();
        Edl.Segment body = edl.segments().get(1);
        assertThat(body.kind()).isEqualTo(Kind.REAL);
        assertThat(body.input()).isEqualTo("v1");
        assertThat(body.in()).isEqualTo(1.0);
        assertThat(body.out()).isEqualTo(9.0); // 8s body inside the [1,9] window
        assertThat(body.hasAudio()).isTrue();
        assertThat(body.blurFill()).isFalse();
        Edl.Segment captions = edl.segments().get(2);
        assertThat(captions.kind()).isEqualTo(Kind.REAL);
        assertThat(captions.input()).isEqualTo("v2");
        assertThat(captions.in()).isEqualTo(2.0);
        assertThat(captions.out()).isEqualTo(7.0);
        Edl.Segment endcard = edl.segments().get(3);
        assertThat(endcard.kind()).isEqualTo(Kind.STILL);
        assertThat(endcard.input()).isEqualTo("endcard");
        assertThat(endcard.in()).isZero();
        assertThat(endcard.out()).isEqualTo(4.0);
        // overlays: hook 0-3, body cap_0 3-11, captions split into 3 slots in [11,16]
        assertThat(edl.overlays()).extracting(Edl.Overlay::input)
                .containsExactly("cap_hook", "cap_0", "cap_1", "cap_2", "cap_3");
        Edl.Overlay bodyOverlay = edl.overlays().get(1);
        assertThat(bodyOverlay.start()).isEqualTo(3.0);
        assertThat(bodyOverlay.end()).isEqualTo(11.0);
        assertThat(edl.overlays().get(2).start()).isEqualTo(11.0);
        assertThat(edl.overlays().get(4).end()).isCloseTo(16.0, within(0.001));
        // total inside [15, 30]
        assertThat(EdlMath.totalSeconds(edl)).isBetween(15.0, 30.0);
        assertThat(EdlMath.aiRatio(edl)).isEqualTo(0.15); // 3 / 20
    }

    @Test
    void plan_withoutH3_hookIsRealWindow_ratioZero() {
        PlanInput in = demo(Map.of(
                "v1", portrait(20), "v2", portrait(20), "v3", portrait(20)), false,
                new Window(1, 9), 3);

        Edl edl = VideoPlanner.plan(in, props);

        Edl.Segment hook = edl.segments().get(0);
        assertThat(hook.kind()).isEqualTo(Kind.REAL);
        assertThat(hook.input()).isEqualTo("v1");
        assertThat(hook.in()).isZero();
        assertThat(hook.out()).isEqualTo(3.0);
        assertThat(EdlMath.aiRatio(edl)).isZero();
        assertThat(EdlMath.mixed(edl)).isFalse();
    }

    @Test
    void plan_landscapeSource_blurFillTrue_portraitFalse() {
        Map<String, SourceClip> sources = Map.of(
                "v1", landscape(20), "v2", portrait(20), "v3", portrait(20));
        Edl edl = VideoPlanner.plan(demo(sources, true, new Window(1, 9), 3), props);

        assertThat(edl.segments().get(1).blurFill()).isTrue();   // landscape v1 body
        assertThat(edl.segments().get(2).blurFill()).isFalse();  // portrait v2 captions
    }

    @Test
    void plan_shortV1_shrinksBody_thenTooShort409() {
        // V1 lasts 6s: body can only be 6s < the 8s minimum → 409
        PlanInput in = demo(Map.of(
                "v1", portrait(6), "v2", portrait(20), "v3", portrait(20)), true,
                new Window(0, 6), 3);

        assertThatThrownBy(() -> VideoPlanner.plan(in, props))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("VIDEO_SOURCE_TOO_SHORT");
                    assertThat(ex.getDetails()).containsEntry("shot", "v1");
                    assertThat(ex.getDetails()).containsEntry("neededSeconds", 8.0);
                    assertThat(ex.getDetails()).containsEntry("actualSeconds", 6.0);
                });
    }

    @Test
    void plan_unboxing_usesV3ForHookAndBody() {
        Map<String, SourceClip> sources = Map.of(
                "v1", portrait(20), "v2", portrait(20), "v3", portrait(20));
        PlanInput in = new PlanInput(VideoType.UNBOXING, sources, false,
                new Windows(new Window(0, 3), new Window(0, 9), new Window(2, 7)), 3, null);

        Edl edl = VideoPlanner.plan(in, props);

        assertThat(edl.segments().get(0).input()).isEqualTo("v3");
        assertThat(edl.segments().get(1).input()).isEqualTo("v3");
        assertThat(edl.segments().get(2).input()).isEqualTo("v2");
        assertThat(edl.musicInput()).isNull();
    }
}
