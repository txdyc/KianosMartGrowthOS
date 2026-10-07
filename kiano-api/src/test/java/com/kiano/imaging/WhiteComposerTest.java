package com.kiano.imaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class WhiteComposerTest {

    /** A red rounded rectangle centred on a transparent canvas — stands in for a cutout. */
    static BufferedImage redRoundedRect(int w, int h, int rectW, int rectH) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(200, 30, 30, 255));
        g.fillRoundRect((w - rectW) / 2, (h - rectH) / 2, rectW, rectH, 40, 40);
        g.dispose();
        return img;
    }

    @Test
    void compose_centersAndHitsOccupancy() {
        BufferedImage cutout = redRoundedRect(1000, 800, 600, 400);
        WhiteComposer.Composed composed = WhiteComposer.compose(cutout, 1600, 0.825);
        BufferedImage out = composed.image();
        assertThat(out.getWidth()).isEqualTo(1600);
        assertThat(out.getHeight()).isEqualTo(1600);
        assertThat(composed.occupancy()).isCloseTo(0.825, within(0.005));
        assertThat(out.getRGB(0, 0)).isEqualTo(0xFFFFFFFF);
        assertThat(out.getRGB(1599, 0)).isEqualTo(0xFFFFFFFF);
        assertThat(out.getRGB(0, 1599)).isEqualTo(0xFFFFFFFF);
        assertThat(out.getRGB(1599, 1599)).isEqualTo(0xFFFFFFFF);
    }

    @Test
    void compose_edgesStayPureWhite() {
        BufferedImage cutout = redRoundedRect(1000, 800, 600, 400);
        WhiteComposer.Composed composed = WhiteComposer.compose(cutout, 1600, 0.825);
        assertThat(WhiteChecks.edgesPureWhite(composed.image(), 2)).isTrue();
    }

    @Test
    void emptyCutout_throwsCutoutEmpty() {
        BufferedImage empty = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
        assertThatThrownBy(() -> WhiteComposer.compose(empty, 1600, 0.825))
                .isInstanceOf(CutoutException.class)
                .extracting(e -> ((CutoutException) e).code())
                .isEqualTo("CUTOUT_EMPTY");
    }

    @Test
    void fullFrameCutout_throwsFullFrame() {
        BufferedImage full = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = full.createGraphics();
        g.setColor(new Color(200, 30, 30, 255));
        g.fillRect(0, 0, 400, 300);
        g.dispose();
        assertThatThrownBy(() -> WhiteComposer.compose(full, 1600, 0.825))
                .isInstanceOf(CutoutException.class)
                .extracting(e -> ((CutoutException) e).code())
                .isEqualTo("CUTOUT_FULL_FRAME");
    }
}
