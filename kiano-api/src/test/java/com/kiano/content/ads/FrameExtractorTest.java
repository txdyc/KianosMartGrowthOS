package com.kiano.content.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.content.media.MediaProperties;
import com.kiano.platform.web.ApiException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * FrameExtractor against a real ffmpeg: three candidate frames ranked by
 * sharpness (a blurred-sharp-blurred video picks the middle frame first),
 * auto-rotated portrait frames, and the 422 when nothing can be extracted.
 * Only runs when an ffmpeg binary is on PATH.
 */
@EnabledIf("ffmpegAvailable")
class FrameExtractorTest {

    private static final int CHECKER = 40;

    private final FrameExtractor extractor = new FrameExtractor(new MediaProperties());

    static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            return p.waitFor() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    @Test
    void returnsThreeFrames_sortedSharpestFirst() throws Exception {
        Path dir = Files.createTempDirectory("ad-frames");
        try {
            Path sharp = dir.resolve("sharp.jpg");
            Path blurry = dir.resolve("blurry.jpg");
            ImageIO.write(checkerboard(), "jpg", sharp.toFile());
            ImageIO.write(blur(checkerboard()), "jpg", blurry.toFile());
            Path video = concat(dir,
                    segment(dir, "seg0.mp4", blurry),
                    segment(dir, "seg1.mp4", sharp),
                    segment(dir, "seg2.mp4", blurry));

            List<FrameExtractor.Frame> frames = extractor.candidates(video, 6.0);

            assertThat(frames).hasSize(3);
            // the sharp middle segment wins even though it is the second candidate
            assertThat(frames.get(0).index()).isEqualTo(1);
            assertThat(frames.get(0).timeSeconds()).isEqualTo(3.0);
            List<FrameExtractor.Frame> sorted = frames.stream()
                    .sorted(Comparator.comparingDouble(FrameExtractor.Frame::blurVariance)
                            .reversed())
                    .toList();
            assertThat(frames).isEqualTo(sorted);
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void rotatedPortraitVideo_framesUpright() throws Exception {
        Path dir = Files.createTempDirectory("ad-rotate");
        try {
            // a portrait 9:16 stream (as a phone records V1): the extracted
            // frame must come out upright, never rotated back to landscape
            Path video = dir.resolve("portrait.mp4");
            run(new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi",
                    "-i", "testsrc=size=1920x1080:rate=30:duration=1",
                    "-vf", "transpose=1", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                    video.toString()));

            List<FrameExtractor.Frame> frames = extractor.candidates(video, 1.0);

            assertThat(frames).isNotEmpty();
            BufferedImage first = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(frames.get(0).jpeg()));
            // the frame is 1080x1920: upright portrait, not the 1920x1080 source
            assertThat(first.getWidth()).isEqualTo(1080);
            assertThat(first.getHeight()).isEqualTo(1920);
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    void unreadableVideo_422() {
        assertThatThrownBy(() -> extractor.candidates(
                Path.of("does-not-exist.mp4"), 5.0))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("DEMO_FRAME_UNAVAILABLE");
                });
    }

    // ---- helpers ----

    private static Path segment(Path dir, String name, Path image) throws Exception {
        Path out = dir.resolve(name);
        run(new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-loop", "1", "-t", "2",
                "-i", image.toString(), "-pix_fmt", "yuv420p", out.toString()));
        return out;
    }

    private static Path concat(Path dir, Path first, Path second, Path third) throws Exception {
        Path list = dir.resolve("list.txt");
        Files.writeString(list, "file '" + first.getFileName() + "'\n"
                + "file '" + second.getFileName() + "'\n"
                + "file '" + third.getFileName() + "'\n");
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

    private static BufferedImage checkerboard() {
        BufferedImage image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        for (int y = 0; y < 480; y += CHECKER) {
            for (int x = 0; x < 640; x += CHECKER) {
                g.setColor(((x / CHECKER + y / CHECKER) % 2 == 0)
                        ? Color.BLACK : Color.WHITE);
                g.fillRect(x, y, CHECKER, CHECKER);
            }
        }
        g.dispose();
        return image;
    }

    private static BufferedImage blur(BufferedImage source) {
        int radius = 12;
        int size = radius * 2 + 1;
        float[] kernel = new float[size * size];
        float weight = 1f / (size * size);
        java.util.Arrays.fill(kernel, weight);
        return new ConvolveOp(new Kernel(size, size, kernel), ConvolveOp.EDGE_NO_OP, null)
                .filter(source, null);
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }
}