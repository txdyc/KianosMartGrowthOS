package com.kiano.imaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class SceneCanvasComposerTest {

    @Test
    void sceneCanvas_maskMatchesProductAndBottomAnchored() {
        BufferedImage cutout = WhiteComposerTest.redRoundedRect(1000, 800, 600, 400);
        SceneCanvasComposer.SceneCanvas canvas = SceneCanvasComposer.compose(cutout, 1600, 0.60, 0.12);
        BufferedImage image = canvas.image();
        BufferedImage mask = canvas.productMask();

        assertThat(image.getWidth()).isEqualTo(1600);
        assertThat(image.getHeight()).isEqualTo(1600);
        assertThat(mask.getWidth()).isEqualTo(1600);
        assertThat(mask.getHeight()).isEqualTo(1600);

        // Background away from the product is grey RGB(128,128,128).
        assertThat(image.getRGB(20, 20) & 0xFFFFFF).isEqualTo(0x808080);
        assertThat(image.getRGB(1579, 1579) & 0xFFFFFF).isEqualTo(0x808080);

        // Product scaled so its long side is 1600 * 0.60 = 960 (crop is 600x400 -> 960x640),
        // horizontally centred and anchored 1600 * 0.12 = 192 above the bottom edge.
        CutoutGeometry.Bounds b = whiteBounds(mask);
        assertThat(b.y() + b.height()).isCloseTo(1600 - 192, within(2));
        assertThat(b.y()).isCloseTo(1600 - 192 - 640, within(4));
        assertThat(b.x()).isCloseTo((1600 - 960) / 2, within(4));

        // Every white mask pixel carries product pixels (antialiased edges outside
        // the mask may blend towards grey, but never be pure grey inside the mask).
        for (int y = 0; y < 1600; y += 7) {
            for (int x = 0; x < 1600; x += 7) {
                boolean inMask = (mask.getRGB(x, y) & 0xFF) >= 128;
                if (inMask) {
                    assertThat(image.getRGB(x, y) & 0xFFFFFF)
                            .as("product pixel expected inside mask at %s,%s", x, y)
                            .isNotEqualTo(0x808080);
                }
            }
        }
    }

    private static CutoutGeometry.Bounds whiteBounds(BufferedImage mask) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                if ((mask.getRGB(x, y) & 0xFF) >= 128) {
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
        return new CutoutGeometry.Bounds(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }
}
