package com.kiano.imaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class WhiteChecksTest {

    private static BufferedImage whiteCanvasWithRedRect(int w, int h, int x, int y, int rw, int rh) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(200, 30, 30));
        g.fillRect(x, y, rw, rh);
        g.dispose();
        return img;
    }

    @Test
    void occupancy_measuresNonWhiteBounds() {
        // Red rect 1320x1100 inside a white 1600x1600 canvas -> long side 1320 / 1600.
        BufferedImage img = whiteCanvasWithRedRect(1600, 1600, 100, 200, 1320, 1100);
        assertThat(WhiteChecks.occupancy(img, 2)).isCloseTo(0.825, within(0.001));
    }

    @Test
    void occupancy_thresholdTreatsNearWhiteAsWhite() {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 100);
        // 253 is white for threshold 2 (255 - 2), 250 is not.
        g.setColor(new Color(253, 253, 253));
        g.fillRect(50, 50, 40, 40);
        g.setColor(new Color(250, 250, 250));
        g.fillRect(10, 10, 20, 20);
        g.dispose();
        // Only the 250 rect counts (253 is white for threshold 2): bbox 20x20.
        assertThat(WhiteChecks.occupancy(img, 2)).isCloseTo(0.20, within(0.005));
    }

    @Test
    void edgesPureWhite_centeredRect_true() {
        BufferedImage img = whiteCanvasWithRedRect(1600, 1600, 200, 200, 1200, 1200);
        assertThat(WhiteChecks.edgesPureWhite(img, 2)).isTrue();
    }

    @Test
    void edgesPureWhite_rectTouchingEdge_false() {
        BufferedImage img = whiteCanvasWithRedRect(1600, 1600, 1, 200, 1200, 1200);
        assertThat(WhiteChecks.edgesPureWhite(img, 2)).isFalse();
    }
}
