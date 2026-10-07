package com.kiano.imaging;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Composes a cutout onto a pure-white square canvas: crop to the alpha bounds,
 * scale so the long side hits the target occupancy, centre, alpha-blend. No
 * shadow, no AI — the product pixels are copied verbatim.
 */
public final class WhiteComposer {

    /** Threshold used when measuring the occupancy of the composed page. */
    static final int NON_WHITE_THRESHOLD = 2;

    private WhiteComposer() {
    }

    /** A composed white page plus its measured product occupancy. */
    public record Composed(BufferedImage image, double occupancy) {
    }

    public static Composed compose(BufferedImage cutout, int canvas, double occupancy) {
        CutoutGeometry.requireUsable(cutout);
        BufferedImage scaled = scaleToLongSide(cutout, canvas, occupancy);
        int x = (canvas - scaled.getWidth()) / 2;
        int y = (canvas - scaled.getHeight()) / 2;
        BufferedImage out = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, canvas, canvas);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(scaled, x, y, null);
        g.dispose();
        return new Composed(out, WhiteChecks.occupancy(out, NON_WHITE_THRESHOLD));
    }

    /**
     * Crops the cutout to its alpha bounding box and scales it so the long side
     * is round(canvas * occupancy). Shared with {@link SceneCanvasComposer}.
     */
    static BufferedImage scaleToLongSide(BufferedImage cutout, int canvas, double occupancy) {
        CutoutGeometry.Bounds bounds = CutoutGeometry.alphaBounds(cutout, CutoutGeometry.ALPHA_THRESHOLD);
        BufferedImage cropped = cutout.getSubimage(bounds.x(), bounds.y(), bounds.width(), bounds.height());
        int longSide = Math.max(1, Math.round((float) (canvas * occupancy)));
        double scale = (double) longSide / Math.max(cropped.getWidth(), cropped.getHeight());
        int tw = Math.max(1, (int) Math.round(cropped.getWidth() * scale));
        int th = Math.max(1, (int) Math.round(cropped.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(cropped, 0, 0, tw, th, null);
        g.dispose();
        return scaled;
    }
}
