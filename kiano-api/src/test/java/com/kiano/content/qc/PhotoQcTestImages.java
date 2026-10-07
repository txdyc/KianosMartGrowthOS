package com.kiano.content.qc;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * Synthesized test images for PhotoQcTest. The checkerboard uses near-black
 * (20) and near-white (235) cells so that a healthy, sharp image stays below
 * the exposure clipping ratios.
 */
final class PhotoQcTestImages {

    private static final int CELL = 8;
    private static final int DARK = 20;
    private static final int BRIGHT = 235;

    private PhotoQcTestImages() {
    }

    /**
     * A checkerboard image that can feed both JPEG encoding and the blur
     * helper without re-reading pixels from a file.
     */
    static final class Checkerboard {

        private final BufferedImage image;

        Checkerboard(int width, int height) {
            image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    boolean bright = ((x / CELL) + (y / CELL)) % 2 == 0;
                    pixels[y * width + x] = gray(bright ? BRIGHT : DARK);
                }
            }
            image.setRGB(0, 0, width, height, pixels, 0, width);
        }

        BufferedImage image() {
            return image;
        }
    }

    static BufferedImage solid(int width, int height, int level) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = gray(level);
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    /**
     * Separable box blur with a (2*radius+1) x (2*radius+1) window, edges
     * clamped. Equivalent to a full 2D box mean.
     */
    static BufferedImage boxBlur(Checkerboard source, int radius) {
        BufferedImage src = source.image();
        int width = src.getWidth();
        int height = src.getHeight();
        int window = 2 * radius + 1;

        float[] gray = new float[width * height];
        int[] pixels = src.getRGB(0, 0, width, height, null, 0, width);
        for (int i = 0; i < pixels.length; i++) {
            gray[i] = luminance(pixels[i]);
        }

        float[] horizontal = new float[width * height];
        for (int y = 0; y < height; y++) {
            float sum = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                sum += gray[y * width + clamp(dx, width)];
            }
            for (int x = 0; x < width; x++) {
                horizontal[y * width + x] = sum / window;
                sum -= gray[y * width + clamp(x - radius, width)];
                sum += gray[y * width + clamp(x + radius + 1, width)];
            }
        }

        float[] blurred = new float[width * height];
        for (int x = 0; x < width; x++) {
            float sum = 0;
            for (int dy = -radius; dy <= radius; dy++) {
                sum += horizontal[clamp(dy, height) * width + x];
            }
            for (int y = 0; y < height; y++) {
                blurred[y * width + x] = sum / window;
                sum -= horizontal[clamp(y - radius, height) * width + x];
                sum += horizontal[clamp(y + radius + 1, height) * width + x];
            }
        }

        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] out = new int[width * height];
        for (int i = 0; i < out.length; i++) {
            out[i] = gray(Math.round(blurred[i]));
        }
        result.setRGB(0, 0, width, height, out, 0, width);
        return result;
    }

    static void writeJpeg(BufferedImage image, Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            ImageIO.write(image, "jpg", out);
        }
    }

    static void writeJpeg(Checkerboard checkerboard, Path file) throws IOException {
        writeJpeg(checkerboard.image(), file);
    }

    private static int gray(int level) {
        int v = Math.max(0, Math.min(255, level));
        return 0xFF000000 | (v << 16) | (v << 8) | v;
    }

    private static float luminance(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max - 1, value));
    }
}
