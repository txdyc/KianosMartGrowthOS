package com.kiano.imaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MaskedSsimTest {

    private static BufferedImage noise(int w, int h, long seed) {
        Random random = new Random(seed);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = random.nextInt(256);
                img.setRGB(x, y, new Color(v, v, v).getRGB());
            }
        }
        return img;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** White rectangle (the product mask) on a black canvas. */
    private static BufferedImage maskRect(int size, int x, int y, int w, int h) {
        BufferedImage mask = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = mask.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, size, size);
        g.setColor(Color.WHITE);
        g.fillRect(x, y, w, h);
        g.dispose();
        return mask;
    }

    @Test
    void ssim_identicalInMask_isOne() {
        BufferedImage a = noise(256, 256, 42);
        BufferedImage mask = maskRect(256, 64, 64, 128, 128);
        double ssim = MaskedSsim.compute(a, a, mask, 4);
        assertThat(ssim).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void ssim_productAltered_dropsBelow0_6() {
        BufferedImage a = noise(256, 256, 42);
        BufferedImage b = copy(a);
        Graphics2D g = b.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(64, 64, 128, 128);
        g.dispose();
        BufferedImage mask = maskRect(256, 64, 64, 128, 128);
        assertThat(MaskedSsim.compute(a, b, mask, 4)).isLessThan(0.6);
    }

    @Test
    void ssim_changesOutsideMask_ignored() {
        BufferedImage a = noise(256, 256, 42);
        BufferedImage b = copy(a);
        Graphics2D g = b.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 40, 40);
        g.fillRect(220, 220, 30, 30);
        g.dispose();
        BufferedImage mask = maskRect(256, 64, 64, 128, 128);
        double ssim = MaskedSsim.compute(a, b, mask, 4);
        assertThat(ssim).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void ssim_tinyMask_isNaN() {
        BufferedImage a = noise(256, 256, 42);
        BufferedImage mask = maskRect(256, 100, 100, 20, 20);
        assertThat(MaskedSsim.compute(a, a, mask, 4)).isNaN();
    }
}
