package com.kiano.content.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Precheck rules on synthetic images with a fake OCR detector. */
class PrecheckTest {

    @Test
    void white_compliant_noFlags() {
        PrecheckResult result = precheck().white(whitePage(1600, 1320), true);
        assertThat(result.flags()).isEmpty();
        assertThat((double) result.metrics().get("occupancy")).isEqualTo(0.825);
        assertThat(result.metrics().get("ssim")).isNull();
        assertThat(result.metrics().get("ocr")).isEqualTo("SKIPPED");
        assertThat(result.metrics().get("ocrWords")).isEqualTo(0);
    }

    @Test
    void white_greyEdge_flagsEdgeNotWhite() {
        BufferedImage img = whitePage(1600, 1320);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(200, 200, 200));
        g.fillRect(0, 0, 10, 10);
        g.dispose();
        PrecheckResult result = precheck().white(img, true);
        assertThat(result.flags()).contains(PrecheckFlag.EDGE_NOT_WHITE);
    }

    @Test
    void white_occupancy90_flags() {
        PrecheckResult result = precheck().white(whitePage(1600, 1440), true);
        assertThat(result.flags()).containsExactly(PrecheckFlag.OCCUPANCY_OUT_OF_RANGE);
        assertThat((double) result.metrics().get("occupancy")).isEqualTo(0.9);
    }

    @Test
    void scene_productPasteBack_noFlags() {
        // Same product pixels, different background: no flags.
        BufferedImage sceneInput = sceneImage(new Color(128, 128, 128));
        BufferedImage output = sceneImage(new Color(30, 60, 120));
        PrecheckResult result = precheck().scene(output, sceneInput, productMask());
        assertThat(result.flags()).isEmpty();
        assertThat((double) result.metrics().get("ssim")).isGreaterThan(0.99);
    }

    @Test
    void scene_productAltered_flagsMismatch() {
        BufferedImage sceneInput = sceneImage(new Color(128, 128, 128));
        BufferedImage output = alteredProductImage();
        PrecheckResult result = precheck().scene(output, sceneInput, productMask());
        assertThat(result.flags()).containsExactly(PrecheckFlag.PRODUCT_MISMATCH);
        assertThat((double) result.metrics().get("ssim")).isLessThan(0.90);
    }

    @Test
    void scene_textOutsideProduct_flagsAiText() {
        // "FAN" has 3 alphanumerics with conf >= 60 -> AI_TEXT; "x" is too short.
        FakeDetector detector = new FakeDetector(true, List.of(
                new TextDetector.OcrWord("FAN", 95.0),
                new TextDetector.OcrWord("x", 99.0)));
        BufferedImage sceneInput = sceneImage(new Color(128, 128, 128));
        BufferedImage output = sceneImage(new Color(30, 60, 120));
        PrecheckResult result = new Precheck(new PrecheckProperties(), detector)
                .scene(output, sceneInput, productMask());
        assertThat(result.flags()).containsExactly(PrecheckFlag.AI_TEXT);
        assertThat(result.metrics().get("ocr")).isEqualTo("OK");
        assertThat(result.metrics().get("ocrWords")).isEqualTo(2);
    }

    @Test
    void scene_ocrDisabled_metricsSkipped() {
        BufferedImage sceneInput = sceneImage(new Color(128, 128, 128));
        BufferedImage output = sceneImage(new Color(30, 60, 120));
        PrecheckResult result = precheck().scene(output, sceneInput, productMask());
        assertThat(result.flags()).isEmpty();
        assertThat(result.metrics().get("ocr")).isEqualTo("SKIPPED");
        assertThat(result.metrics().get("ocrWords")).isEqualTo(0);
        assertThat(result.metrics().get("occupancy")).isNull();
    }

    private Precheck precheck() {
        return new Precheck(new PrecheckProperties(), new FakeDetector(false, List.of()));
    }

    /** 1600x1600 white canvas with a centered red product square. */
    private static BufferedImage whitePage(int canvas, int product) {
        BufferedImage img = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, canvas, canvas);
        g.setColor(Color.RED);
        int off = (canvas - product) / 2;
        g.fillRect(off, off, product, product);
        g.dispose();
        return img;
    }

    /** 1600x1600 scene with a red 400x400 product at (200,200). */
    private static BufferedImage sceneImage(Color background) {
        BufferedImage img = new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, 1600, 1600);
        g.setColor(Color.RED);
        g.fillRect(200, 200, 400, 400);
        g.dispose();
        return img;
    }

    /** Same scene but the product turned green. */
    private static BufferedImage alteredProductImage() {
        BufferedImage img = new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(128, 128, 128));
        g.fillRect(0, 0, 1600, 1600);
        g.setColor(Color.GREEN);
        g.fillRect(200, 200, 400, 400);
        g.dispose();
        return img;
    }

    /** White 400x400 product mask at (200,200) on black. */
    private static BufferedImage productMask() {
        BufferedImage img = new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 1600, 1600);
        g.setColor(Color.WHITE);
        g.fillRect(200, 200, 400, 400);
        g.dispose();
        return img;
    }

    private static final class FakeDetector implements TextDetector {

        private final boolean enabled;
        private final List<OcrWord> words;

        FakeDetector(boolean enabled, List<OcrWord> words) {
            this.enabled = enabled;
            this.words = words;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public List<OcrWord> detect(Path png) {
            return words;
        }
    }
}
