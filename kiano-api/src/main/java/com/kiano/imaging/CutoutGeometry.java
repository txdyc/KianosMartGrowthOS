package com.kiano.imaging;

import java.awt.image.BufferedImage;

/**
 * Geometry helpers over the alpha channel of an RGBA cutout. Pure functions,
 * no Spring and no IO.
 */
public final class CutoutGeometry {

    /** Alpha threshold below which a pixel counts as background. */
    public static final int ALPHA_THRESHOLD = 8;

    private CutoutGeometry() {
    }

    /** Axis-aligned bounding box of the product inside an RGBA cutout. */
    public record Bounds(int x, int y, int width, int height) {
    }

    /** Bounding box of pixels with alpha above the threshold; null when none. */
    public static Bounds alphaBounds(BufferedImage rgba, int alphaThreshold) {
        int w = rgba.getWidth();
        int h = rgba.getHeight();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            rgba.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) > alphaThreshold) {
                    if (x < minX) {
                        minX = x;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }
        if (maxX < 0) {
            return null;
        }
        return new Bounds(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /** Ratio of pixels with alpha above the threshold, 0..1. */
    public static double alphaCoverage(BufferedImage rgba, int alphaThreshold) {
        int w = rgba.getWidth();
        int h = rgba.getHeight();
        long opaque = 0;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            rgba.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) > alphaThreshold) {
                    opaque++;
                }
            }
        }
        return (double) opaque / ((long) w * h);
    }

    /**
     * Rejects unusable cutouts: alpha coverage below 1% means background removal
     * swallowed the product (CUTOUT_EMPTY); a bounding box that touches all four
     * frame edges means nothing was removed at all (CUTOUT_FULL_FRAME).
     */
    public static void requireUsable(BufferedImage rgba) {
        if (alphaCoverage(rgba, ALPHA_THRESHOLD) < 0.01) {
            throw new CutoutException("CUTOUT_EMPTY");
        }
        Bounds bounds = alphaBounds(rgba, ALPHA_THRESHOLD);
        int w = rgba.getWidth();
        int h = rgba.getHeight();
        boolean touchesAllEdges = bounds != null
                && bounds.x() <= 2
                && bounds.y() <= 2
                && bounds.x() + bounds.width() >= w - 2
                && bounds.y() + bounds.height() >= h - 2;
        if (touchesAllEdges) {
            throw new CutoutException("CUTOUT_FULL_FRAME");
        }
    }
}
