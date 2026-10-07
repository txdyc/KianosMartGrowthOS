package com.kiano.imaging;

import java.awt.image.BufferedImage;

/**
 * Compliance checks for white-background product pages. Pure functions.
 */
public final class WhiteChecks {

    private WhiteChecks() {
    }

    /**
     * True when every pixel of the border ring (borderPx wide on each side) is
     * exactly pure white.
     */
    public static boolean edgesPureWhite(BufferedImage img, int borderPx) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (borderPx <= 0) {
            return true;
        }
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            boolean fullRow = y < borderPx || y >= h - borderPx;
            img.getRGB(0, y, w, 1, row, 0, w);
            int xEnd = fullRow ? w : borderPx;
            for (int x = 0; x < xEnd; x++) {
                if (row[x] != 0xFFFFFFFF) {
                    return false;
                }
            }
            if (!fullRow) {
                for (int x = w - borderPx; x < w; x++) {
                    if (row[x] != 0xFFFFFFFF) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Product occupancy: long side of the non-white bounding box divided by the
     * canvas side. A pixel is non-white when any channel is more than
     * nonWhiteThreshold below 255.
     */
    public static double occupancy(BufferedImage img, int nonWhiteThreshold) {
        int w = img.getWidth();
        int h = img.getHeight();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        int floor = 255 - nonWhiteThreshold;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int rgb = row[x];
                if ((rgb >>> 16 & 0xFF) < floor || (rgb >>> 8 & 0xFF) < floor || (rgb & 0xFF) < floor) {
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
            return 0.0;
        }
        long side = Math.max(maxX - minX + 1L, maxY - minY + 1L);
        return (double) side / Math.max(w, h);
    }
}
