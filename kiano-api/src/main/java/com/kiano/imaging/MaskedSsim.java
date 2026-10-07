package com.kiano.imaging;

import java.awt.image.BufferedImage;

/**
 * Structural similarity restricted to the product mask: 8x8 windows (step 4)
 * that fall entirely inside the eroded mask are scored with the standard SSIM
 * constants and averaged. Fewer than 16 valid windows yields NaN.
 */
public final class MaskedSsim {

    private static final double C1 = (0.01 * 255) * (0.01 * 255);
    private static final double C2 = (0.03 * 255) * (0.03 * 255);
    private static final int WINDOW = 8;
    private static final int STEP = 4;
    private static final int MIN_WINDOWS = 16;

    private MaskedSsim() {
    }

    public static double compute(BufferedImage a, BufferedImage b, BufferedImage mask, int erodePx) {
        int w = a.getWidth();
        int h = a.getHeight();
        double[] ga = luma(a);
        double[] gb = luma(b);
        boolean[] eroded = erode(mask, w, h, erodePx);

        double sum = 0;
        int count = 0;
        for (int wy = 0; wy + WINDOW <= h; wy += STEP) {
            for (int wx = 0; wx + WINDOW <= w; wx += STEP) {
                if (!allInside(eroded, w, wx, wy)) {
                    continue;
                }
                sum += windowSsim(ga, gb, w, wx, wy);
                count++;
            }
        }
        return count < MIN_WINDOWS ? Double.NaN : sum / count;
    }

    private static double windowSsim(double[] ga, double[] gb, int w, int wx, int wy) {
        double sumX = 0;
        double sumY = 0;
        double sumXX = 0;
        double sumYY = 0;
        double sumXY = 0;
        for (int y = wy; y < wy + WINDOW; y++) {
            for (int x = wx; x < wx + WINDOW; x++) {
                double xv = ga[y * w + x];
                double yv = gb[y * w + x];
                sumX += xv;
                sumY += yv;
                sumXX += xv * xv;
                sumYY += yv * yv;
                sumXY += xv * yv;
            }
        }
        double n = WINDOW * WINDOW;
        double muX = sumX / n;
        double muY = sumY / n;
        double varX = sumXX / n - muX * muX;
        double varY = sumYY / n - muY * muY;
        double covXY = sumXY / n - muX * muY;
        double num = (2 * muX * muY + C1) * (2 * covXY + C2);
        double den = (muX * muX + muY * muY + C1) * (varX + varY + C2);
        return num / den;
    }

    private static boolean allInside(boolean[] eroded, int w, int wx, int wy) {
        for (int y = wy; y < wy + WINDOW; y++) {
            for (int x = wx; x < wx + WINDOW; x++) {
                if (!eroded[y * w + x]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Square (separable) binary erosion by erodePx pixels. */
    private static boolean[] erode(BufferedImage mask, int w, int h, int r) {
        boolean[] base = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                base[y * w + x] = (mask.getRGB(x, y) & 0xFF) >= 128;
            }
        }
        if (r <= 0) {
            return base;
        }
        boolean[] horizontal = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean ok = true;
                for (int dx = -r; dx <= r && ok; dx++) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= w || !base[y * w + xx]) {
                        ok = false;
                    }
                }
                horizontal[y * w + x] = ok;
            }
        }
        boolean[] full = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean ok = true;
                for (int dy = -r; dy <= r && ok; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h || !horizontal[yy * w + x]) {
                        ok = false;
                    }
                }
                full[y * w + x] = ok;
            }
        }
        return full;
    }

    private static double[] luma(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        double[] out = new double[w * h];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int rgb = row[x];
                out[y * w + x] = 0.299 * (rgb >>> 16 & 0xFF) + 0.587 * (rgb >>> 8 & 0xFF)
                        + 0.114 * (rgb & 0xFF);
            }
        }
        return out;
    }
}
