package com.kiano.content.qc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * VideoQc rules: portrait requirement, 1080p minimum, 30fps window and the
 * V1-only duration window.
 */
class VideoQcTest {

    private final VideoQc qc = new VideoQc(new QcProperties());

    @Test
    void portrait1080p30_v1_15s_ok() {
        assertThat(qc.evaluate("V1", new VideoInfo(1080, 1920, 15, 30))).isEmpty();
    }

    @Test
    void landscape_notPortrait() {
        assertThat(qc.evaluate("V1", new VideoInfo(1920, 1080, 15, 30)))
                .containsExactly(QcReason.NOT_PORTRAIT);
    }

    @Test
    void v1_25s_durationOutOfRange_butV2_25s_ok() {
        assertThat(qc.evaluate("V1", new VideoInfo(1080, 1920, 25, 30)))
                .containsExactly(QcReason.DURATION_OUT_OF_RANGE);
        assertThat(qc.evaluate("V2", new VideoInfo(1080, 1920, 25, 30))).isEmpty();
    }

    @Test
    void fps60_outOfRange() {
        assertThat(qc.evaluate("V1", new VideoInfo(1080, 1920, 15, 60)))
                .containsExactly(QcReason.FPS_OUT_OF_RANGE);
    }

    @Test
    void hd720_lowResolution() {
        assertThat(qc.evaluate("V1", new VideoInfo(720, 1280, 15, 30)))
                .containsExactly(QcReason.LOW_RESOLUTION);
    }
}
