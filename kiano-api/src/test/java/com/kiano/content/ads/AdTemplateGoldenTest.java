package com.kiano.content.ads;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.imaging.ImageCodec;
import com.kiano.content.template.TemplateBootstrap;
import com.kiano.content.template.TemplateRenderer;
import com.kiano.content.template.TemplateRenderer.Rect;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Golden-file tests for the four AD_* overlay templates (4 hooks x 3 sizes)
 * plus the 9:16 safe-area rule and the contain-not-crop guarantee. Run with
 * -Dkiano.golden.update=true to regenerate the golden PNGs, then eyeball the
 * images before committing (same policy as TemplateRendererGoldenTest).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdTemplateGoldenTest {

    private static final int GRAY_TOLERANCE = 32;
    private static final double MAX_DIFF_RATIO = 0.005;
    private static final double NINE_SIXTEEN_TOP_SAFE = 269;
    private static final double NINE_SIXTEEN_BOTTOM_SAFE = 1248;

    @Autowired
    private TemplateRenderer renderer;

    @Autowired
    private TemplateBootstrap bootstrap;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from template");
        bootstrap.bootstrapAll();
        tenantId = jdbcTemplate.queryForObject(
                "select id from tenant where slug = 'kianosmart'", Long.class);
    }

    @ParameterizedTest
    @CsvSource({
            "AD_PRICEHOOK,1080,1080",
            "AD_PRICEHOOK,1080,1350",
            "AD_PRICEHOOK,1080,1920",
            "AD_PROBLEM,1080,1080",
            "AD_PROBLEM,1080,1350",
            "AD_PROBLEM,1080,1920",
            "AD_DEMO,1080,1080",
            "AD_DEMO,1080,1350",
            "AD_DEMO,1080,1920",
            "AD_TRUST,1080,1080",
            "AD_TRUST,1080,1350",
            "AD_TRUST,1080,1920"})
    void matchesGolden(String code, int width, int height) throws Exception {
        byte[] png = renderer.render(tenantId, code, model(code, width, height),
                width, height);
        assertMatchesGolden(Path.of("src/test/resources/golden/ads/"
                + hookName(code) + "-" + width + "x" + height + ".png"), png);
    }

    @Test
    void nineBySixteen_textInsideSafeArea_withLongHeadline() {
        // Review Focus 2: a 40-char overlay must not spill into the top 14% or
        // bottom 35% (y outside [269, 1248]).
        Map<String, Object> model = base("AD_PRICEHOOK", 1080, 1920, "s9x16",
                "x".repeat(40));
        model.put("current", "GH₵ 299");
        model.put("strike", "GH₵ 349");

        List<Rect> boxes = renderer.textBoxes(tenantId, "AD_PRICEHOOK", model, 1080, 1920);

        assertThat(boxes).isNotEmpty();
        assertThat(boxes).allSatisfy(rect -> {
            assertThat(rect.top()).isGreaterThanOrEqualTo(NINE_SIXTEEN_TOP_SAFE);
            assertThat(rect.bottom()).isLessThanOrEqualTo(NINE_SIXTEEN_BOTTOM_SAFE);
        });
    }

    @Test
    void squareBase_notCroppedIn9x16() {
        Map<String, Object> model = base("AD_PRICEHOOK", 1080, 1920, "s9x16",
                "Square product shot");
        model.put("baseImage", base64(squarePng()));
        model.put("current", "GH₵ 299");

        List<Rect> product = renderer.rectsFor(tenantId, "AD_PRICEHOOK", model, 1080, 1920,
                ".product");

        assertThat(product).hasSize(1);
        Rect rect = product.get(0);
        assertThat(rect.right() - rect.left()).isLessThanOrEqualTo(1080);
        // fully visible inside the canvas
        assertThat(rect.top()).isGreaterThanOrEqualTo(0);
        assertThat(rect.bottom()).isLessThanOrEqualTo(1920);
        assertThat(rect.left()).isGreaterThanOrEqualTo(0);
        assertThat(rect.right()).isLessThanOrEqualTo(1080);
    }

    @Test
    void pricehook_strikeOnlyWhenProvided() {
        Map<String, Object> withoutStrike = base("AD_PRICEHOOK", 1080, 1080, "s1x1",
                "Great value");
        withoutStrike.put("current", "GH₵ 299");

        String html = renderer.expanded(tenantId, "AD_PRICEHOOK", withoutStrike);

        // no strike element is rendered when the model has no strike value
        assertThat(html).doesNotContain("class=\"ad-text strike\"");
        assertThat(html).contains("GH₵ 299");
    }

    @Test
    void escapesOverlayHtml() {
        Map<String, Object> model = base("AD_PROBLEM", 1080, 1080, "s1x1", "<b>x</b>");

        String html = renderer.expanded(tenantId, "AD_PROBLEM", model);

        assertThat(html).contains("&lt;b&gt;x&lt;/b&gt;");
        assertThat(html).doesNotContain("<b>x</b>");
    }

    // ---- model helpers ----

    private Map<String, Object> model(String code, int width, int height) {
        String sizeClass = sizeClass(width, height);
        Map<String, Object> model = base(code, width, height, sizeClass, overlay(code));
        switch (code) {
            case "AD_PRICEHOOK" -> {
                model.put("current", "GH₵ 299");
                model.put("strike", "GH₵ 349");
                model.put("endsLabel", "Ends 20 Oct");
            }
            case "AD_TRUST" -> model.put("badges", List.of(
                    Map.of("text", "COD"), Map.of("text", "MTN MoMo"),
                    Map.of("text", "Fast delivery"), Map.of("text", "1 year warranty")));
            default -> {
                // problem / demo only use the common fields
            }
        }
        return model;
    }

    private static Map<String, Object> base(String code, int width, int height,
            String sizeClass, String overlay) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("width", width);
        model.put("height", height);
        model.put("sizeClass", sizeClass);
        model.put("baseImage", base64(productPng()));
        model.put("overlay", overlay);
        model.put("productName", "Morgan Steam Iron");
        model.put("fontScale", 1);
        return model;
    }

    private static String overlay(String code) {
        return switch (code) {
            case "AD_PRICEHOOK" -> "Everyday value for your home";
            case "AD_PROBLEM" -> "Tired of slow ironing?";
            case "AD_DEMO" -> "See it steam in seconds";
            default -> "Why buy with confidence";
        };
    }

    private static String sizeClass(int width, int height) {
        return switch (height) {
            case 1080 -> "s1x1";
            case 1350 -> "s4x5";
            default -> "s9x16";
        };
    }

    private static String hookName(String code) {
        return switch (code) {
            case "AD_PRICEHOOK" -> "pricehook";
            case "AD_PROBLEM" -> "problem";
            case "AD_DEMO" -> "demo";
            default -> "trust";
        };
    }

    private static String base64(byte[] png) {
        return "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(ImageCodec.jpeg(
                        ImageCodec.read(png), 0.88f));
    }

    private static byte[] productPng() {
        BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 400);
        g.setColor(new Color(180, 60, 40));
        g.fillRoundRect(180, 60, 240, 280, 30, 30);
        g.setColor(new Color(230, 230, 230));
        g.fillRect(200, 100, 200, 60);
        g.dispose();
        return ImageCodec.png(img);
    }

    private static byte[] squarePng() {
        BufferedImage img = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 600);
        g.setColor(new Color(40, 90, 120));
        g.fillRoundRect(120, 120, 360, 360, 40, 40);
        g.dispose();
        return ImageCodec.png(img);
    }

    private void assertMatchesGolden(Path golden, byte[] png) throws IOException {
        if (Boolean.getBoolean("kiano.golden.update")) {
            Files.createDirectories(golden.getParent());
            Files.write(golden, png);
            return;
        }
        assertThat(golden).as("golden file (generate with -Dkiano.golden.update=true)")
                .exists();
        BufferedImage expected = ImageCodec.read(Files.readAllBytes(golden));
        BufferedImage actual = ImageCodec.read(png);
        assertThat(actual.getWidth()).isEqualTo(expected.getWidth());
        assertThat(actual.getHeight()).isEqualTo(expected.getHeight());
        long diff = 0;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if (Math.abs(luma(expected.getRGB(x, y)) - luma(actual.getRGB(x, y)))
                        > GRAY_TOLERANCE) {
                    diff++;
                }
            }
        }
        assertThat((double) diff / (expected.getWidth() * expected.getHeight()))
                .as("pixels differing by more than %d gray levels", GRAY_TOLERANCE)
                .isLessThanOrEqualTo(MAX_DIFF_RATIO);
    }

    private static int luma(int rgb) {
        return (int) Math.round(0.299 * (rgb >>> 16 & 0xFF)
                + 0.587 * (rgb >>> 8 & 0xFF) + 0.114 * (rgb & 0xFF));
    }
}