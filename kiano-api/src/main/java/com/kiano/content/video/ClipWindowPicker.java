package com.kiano.content.video;

import com.kiano.content.ads.FrameExtractor;
import com.kiano.content.qc.PhotoQc;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Picks real-footage time windows (C5 T5): {@link #rank} samples the sharpest
 * windows by the Laplacian variance of their midpoint frame, and {@link #pick}
 * rotates through them, skipping windows that would repeat the same footage as
 * the hook when body comes from the same clip.
 */
@Component
public class ClipWindowPicker {

    /** One candidate window: source timecodes plus its sharpness score. */
    public record Window(double in, double out, double score) {
    }

    private final FrameExtractor frameExtractor;

    public ClipWindowPicker(FrameExtractor frameExtractor) {
        this.frameExtractor = frameExtractor;
    }

    /**
     * Evenly samples {@code candidates} start points in [0, duration -
     * windowSeconds] and ranks each window by the blur variance of a frame at
     * its midpoint. A source shorter than the window yields the single full
     * window. Missing frames score -1 so they sort last.
     */
    public List<Window> rank(Path video, double duration, double windowSeconds, int candidates) {
        if (windowSeconds >= duration) {
            double score = scoreAt(video, duration / 2);
            return List.of(new Window(0, duration, score));
        }
        double range = duration - windowSeconds;
        int n = Math.max(1, candidates);
        List<Window> windows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double start = n == 1 ? range / 2 : range * i / (n - 1);
            windows.add(new Window(start, start + windowSeconds, scoreAt(video, start + windowSeconds / 2)));
        }
        windows.sort(Comparator.comparingDouble(Window::score).reversed());
        return List.copyOf(windows);
    }

    /**
     * The {@code candidate % n}-th ranked window, skipping (in rotation order)
     * any window that overlaps {@code avoid} - used when hook and body draw
     * from the same footage so the opening does not reuse the body's frames.
     */
    public Window pick(List<Window> ranked, int candidate, @Nullable Window avoid) {
        if (ranked == null || ranked.isEmpty()) {
            throw new IllegalArgumentException("No windows to pick from");
        }
        int n = ranked.size();
        int start = Math.floorMod(candidate, n);
        if (avoid == null) {
            return ranked.get(start);
        }
        for (int step = 0; step < n; step++) {
            Window window = ranked.get((start + step) % n);
            if (!overlaps(window, avoid)) {
                return window;
            }
        }
        return ranked.get(start); // every window overlaps; keep the rotation pick
    }

    private static boolean overlaps(Window a, Window b) {
        return a.out() > b.in() && a.in() < b.out();
    }

    private double scoreAt(Path video, double time) {
        Optional<byte[]> jpeg = frameExtractor.frameAt(video, time);
        if (jpeg.isEmpty()) {
            return -1;
        }
        BufferedImage image = decode(jpeg.get());
        return image == null ? -1 : PhotoQc.blurVariance(image);
    }

    private static BufferedImage decode(byte[] jpeg) {
        try (InputStream in = new ByteArrayInputStream(jpeg)) {
            return ImageIO.read(in);
        } catch (IOException ex) {
            return null;
        }
    }
}
