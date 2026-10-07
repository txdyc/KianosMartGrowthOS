package com.kiano.content.qc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * FfprobeJsonParser: rotation from side_data_list or tags.rotate swaps coded
 * dimensions to display dimensions, and NTSC frame rates parse exactly.
 */
class FfprobeJsonParserTest {

    @Test
    void sideDataRotation_swapsToPortrait() throws IOException {
        VideoInfo info = FfprobeJsonParser.parse(fixture("portrait-sidedata.json"));

        assertThat(info.width()).isEqualTo(1080);
        assertThat(info.height()).isEqualTo(1920);
        assertThat(info.fps()).isEqualTo(30.0);
        assertThat(info.durationSeconds()).isEqualTo(15.0);

        VideoInfo landscape = FfprobeJsonParser.parse(fixture("landscape.json"));
        assertThat(landscape.width()).isEqualTo(1920);
        assertThat(landscape.height()).isEqualTo(1080);
    }

    @Test
    void tagRotate_swapsToPortrait() throws IOException {
        VideoInfo info = FfprobeJsonParser.parse(fixture("portrait-tag.json"));

        assertThat(info.width()).isEqualTo(1080);
        assertThat(info.height()).isEqualTo(1920);
    }

    @Test
    void ntscFrameRate_parsed() throws IOException {
        VideoInfo info = FfprobeJsonParser.parse(fixture("portrait-tag.json"));

        assertThat(info.fps()).isCloseTo(29.97, within(0.01));
    }

    @Test
    void noVideoStream_throwsUnreadable() {
        String json = "{\"streams\":[{\"codec_type\":\"audio\",\"sample_rate\":\"48000\"}],"
                + "\"format\":{\"duration\":\"10.000000\"}}";

        assertThatThrownBy(() -> FfprobeJsonParser.parse(json))
                .isInstanceOf(UnreadableMediaException.class);
    }

    private String fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/ffprobe/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
