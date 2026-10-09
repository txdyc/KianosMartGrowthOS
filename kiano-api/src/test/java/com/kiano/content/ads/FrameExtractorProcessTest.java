package com.kiano.content.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.content.media.MediaProperties;
import com.kiano.platform.web.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * FrameExtractor process handling without a real ffmpeg: a binary that cannot
 * start must not leave the caller's thread interrupted, and a hanging process
 * is killed after the configured timeout instead of blocking forever.
 */
class FrameExtractorProcessTest {

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void missingBinary_422_andCallerThreadNotInterrupted() {
        MediaProperties properties = new MediaProperties();
        properties.setFfmpegPath("kiano-no-such-ffmpeg-binary");
        FrameExtractor extractor = new FrameExtractor(properties);

        assertThatThrownBy(() -> extractor.candidates(Path.of("missing.mp4"), 6.0))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("DEMO_FRAME_UNAVAILABLE"));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void hangingFfmpeg_isKilledAfterTimeout() throws Exception {
        Path dir = Files.createTempDirectory("fake-ffmpeg");
        Path fake = fakeHangingBinary(dir);
        MediaProperties properties = new MediaProperties();
        properties.setFfmpegPath(fake.toString());
        properties.setFfmpegTimeout(Duration.ofSeconds(1));
        FrameExtractor extractor = new FrameExtractor(properties);

        long start = System.nanoTime();
        assertThatThrownBy(() -> extractor.candidates(dir.resolve("v.mp4"), 6.0))
                .isInstanceOf(ApiException.class);
        long elapsedSeconds = Duration.ofNanos(System.nanoTime() - start).toSeconds();

        // three candidates x 1s timeout; a blocking wait would take 3 x 30s
        assertThat(elapsedSeconds).isLessThan(20);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    private static Path fakeHangingBinary(Path dir) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            Path script = dir.resolve("ffmpeg.cmd");
            Files.writeString(script, "@ping -n 31 127.0.0.1 >nul\r\n");
            return script;
        }
        Path script = dir.resolve("ffmpeg");
        Files.writeString(script, "#!/bin/sh\nsleep 30\n");
        script.toFile().setExecutable(true);
        return script;
    }
}
