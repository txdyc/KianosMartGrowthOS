package com.kiano.content.ads;

import com.kiano.content.media.MediaProperties;
import com.kiano.content.qc.PhotoQc;
import com.kiano.platform.web.ApiException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Picks the sharpest V1 frame for the demo hook: ffmpeg extracts one JPEG at
 * 25 %, 50 % and 75 % of the video; frames are ranked by the same Laplacian
 * variance the photo QC uses. A failed extraction skips that frame; when every
 * frame fails the call throws 422 DEMO_FRAME_UNAVAILABLE. ffmpeg auto-rotates
 * portrait videos, so the frames come out upright.
 */
@Component
public class FrameExtractor {

    private static final double[] FRACTIONS = {0.25, 0.50, 0.75};

    /** One candidate frame: its position, extraction time and sharpness. */
    public record Frame(int index, double timeSeconds, double blurVariance, byte[] jpeg) {
    }

    private final MediaProperties properties;

    public FrameExtractor(MediaProperties properties) {
        this.properties = properties;
    }

    /** Three frames sorted sharpest first; never fewer than one. */
    public List<Frame> candidates(Path video, double durationSeconds) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < FRACTIONS.length; i++) {
            double time = durationSeconds * FRACTIONS[i];
            byte[] jpeg = extract(video, time);
            if (jpeg == null) {
                continue;
            }
            BufferedImage image = decode(jpeg);
            if (image != null) {
                frames.add(new Frame(i, time, PhotoQc.blurVariance(image), jpeg));
            }
        }
        if (frames.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEMO_FRAME_UNAVAILABLE",
                    "No demo frame could be extracted from the V1 video");
        }
        frames.sort(Comparator.comparingDouble(Frame::blurVariance).reversed());
        return frames;
    }

    /**
     * One frame at {@code time}; null when ffmpeg cannot start, fails, writes
     * nothing or exceeds {@code kiano.media.ffmpeg-timeout} (then it is killed).
     * Output is discarded rather than piped so the timed wait cannot block on a
     * full pipe.
     */
    private byte[] extract(Path video, double time) {
        Path out = null;
        Process process = null;
        try {
            out = Files.createTempFile("ad-frame", ".jpg");
            process = new ProcessBuilder(properties.getFfmpegPath(), "-v", "error",
                    "-ss", String.format(Locale.ROOT, "%.3f", time), "-i", video.toString(),
                    "-frames:v", "1", "-q:v", "2", "-y", out.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(properties.getFfmpegTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || Files.size(out) == 0) {
                return null;
            }
            return Files.readAllBytes(out);
        } catch (IOException ex) {
            return null; // ffmpeg missing or unreadable output: skip this frame
        } catch (InterruptedException ex) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
    }

    private static BufferedImage decode(byte[] jpeg) {
        try (InputStream in = new java.io.ByteArrayInputStream(jpeg)) {
            return ImageIO.read(in);
        } catch (IOException ex) {
            return null;
        }
    }
}