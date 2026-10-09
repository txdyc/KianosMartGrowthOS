package com.kiano.content.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.content.ads.FrameExtractor;
import com.kiano.content.media.MediaProperties;
import com.kiano.content.video.ClipWindowPicker.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * ClipWindowPicker (C5 T5) against a real ffmpeg: windows are ranked by the
 * sharpness of their midpoint frame (a video whose first half is blurred ranks
 * its sharp second half first), picks rotate and skip overlap with an avoided
 * window, and a source shorter than the window yields one full window.
 */
@EnabledIf("ffmpegAvailable")
class ClipWindowPickerTest {

    private static final int SHARP_HALF_SECONDS = 4;

    private final ClipWindowPicker picker =
            new ClipWindowPicker(new FrameExtractor(new MediaProperties()));

    static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            return p.waitFor() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    @Test
    void rank_prefersSharpWindows() throws Exception {
        Path dir = Files.createTempDirectory("clip-windows");
        try {
            Path video = blurredThenSharp(dir);
            double duration = SHARP_HALF_SECONDS * 2;

            List<Window> ranked = picker.rank(video, duration, 2.0, 4);

            assertThat(ranked).hasSize(4);
            // the top windows' midpoints sit in the sharp second half
            assertThat(ranked.get(0).in()).isGreaterThanOrEqualTo(SHARP_HALF_SECONDS);
            assertThat(ranked.get(1).in()).isGreaterThanOrEqualTo(SHARP_HALF_SECONDS);
            // and they beat the blurred first-half windows
            assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(3).score());
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void pick_rotatesWithCandidate_andAvoidsOverlap() {
        List<Window> ranked = List.of(
                new Window(0, 2, 0.9),
                new Window(3, 5, 0.5),
                new Window(6, 8, 0.3));

        assertThat(picker.pick(ranked, 0, null)).isEqualTo(ranked.get(0));
        assertThat(picker.pick(ranked, 1, null)).isEqualTo(ranked.get(1));
        assertThat(picker.pick(ranked, 4, null)).isEqualTo(ranked.get(1)); // 4 % 3
        // rotation 0 would take window A, but it overlaps the avoided hook window
        assertThat(picker.pick(ranked, 0, ranked.get(0))).isEqualTo(ranked.get(1));
        // an avoided window that overlaps nothing does not change the pick
        assertThat(picker.pick(ranked, 2, ranked.get(0))).isEqualTo(ranked.get(2));
    }

    @Test
    void shortSource_singleFullWindow() throws Exception {
        Path dir = Files.createTempDirectory("clip-windows-short");
        try {
            Path video = blurredThenSharp(dir);

            List<Window> ranked = picker.rank(video, 3.0, 5.0, 3);

            assertThat(ranked).hasSize(1);
            assertThat(ranked.get(0).in()).isZero();
            assertThat(ranked.get(0).out()).isEqualTo(3.0);
            assertThat(ranked.get(0).score()).isGreaterThanOrEqualTo(0);
        } finally {
            deleteTree(dir);
        }
    }

    /** 4s gblur=sigma=20 followed by 4s sharp, at 1080x1920 30fps. */
    private static Path blurredThenSharp(Path dir) throws Exception {
        Path blurred = dir.resolve("blurred.mp4");
        run(new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi",
                "-i", "testsrc=size=1080x1920:rate=30",
                "-t", String.valueOf(SHARP_HALF_SECONDS),
                "-vf", "gblur=sigma=20", "-pix_fmt", "yuv420p", blurred.toString()));
        Path sharp = dir.resolve("sharp.mp4");
        run(new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi",
                "-i", "testsrc=size=1080x1920:rate=30",
                "-t", String.valueOf(SHARP_HALF_SECONDS),
                "-pix_fmt", "yuv420p", sharp.toString()));
        Path list = dir.resolve("list.txt");
        Files.writeString(list, "file 'blurred.mp4'\nfile 'sharp.mp4'\n");
        Path out = dir.resolve("video.mp4");
        run(new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0",
                "-i", list.toString(), "-c", "copy", out.toString()));
        return out;
    }

    private static void run(ProcessBuilder builder) throws Exception {
        Process process = builder.redirectErrorStream(true).start();
        byte[] sink = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("ffmpeg failed: "
                    + new String(sink, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ignored) {
                    // best effort
                }
            });
        }
    }
}
