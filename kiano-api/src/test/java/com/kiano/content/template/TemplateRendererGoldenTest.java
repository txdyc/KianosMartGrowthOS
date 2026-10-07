package com.kiano.content.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.imaging.ImageCodec;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Golden-file tests for the PAGE_SPEC and PAGE_INFO templates rendered by
 * the real Playwright Chromium (first run downloads the browser). Run with
 * -Dkiano.golden.update=true to regenerate the golden PNGs after an
 * intentional template change, then eyeball the images before committing.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TemplateRendererGoldenTest {

    private static final int CANVAS = 1600;
    private static final int GRAY_TOLERANCE = 32;
    private static final double MAX_DIFF_RATIO = 0.005;

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

    @Test
    void pageSpec_matchesGolden() throws Exception {
        byte[] png = renderer.render(tenantId, "PAGE_SPEC",
                renderer.specModel(facts()), CANVAS, CANVAS);
        assertMatchesGolden(Path.of("src/test/resources/golden/page_spec_v1.png"), png);
    }

    @Test
    void pageInfo_matchesGolden() throws Exception {
        byte[] png = renderer.render(tenantId, "PAGE_INFO",
                renderer.infoModel(facts(), productPng()), CANVAS, CANVAS);
        assertMatchesGolden(Path.of("src/test/resources/golden/page_info_v1.png"), png);
    }

    @Test
    void nullFacts_rowsOmitted_notPrintedAsNull() {
        TemplateFacts sparse = new TemplateFacts("Morgan Steam Iron", null, null, null,
                null, null, null, null, null, null, null, null);
        String body = bodyOf(renderer.expanded(tenantId, "PAGE_SPEC",
                renderer.specModel(sparse)));
        assertThat(body).doesNotContain("null");
        assertThat(body).doesNotContain("Power");
    }

    @Test
    void longFeature_wrapsWithinCanvas() {
        TemplateFacts wordy = new TemplateFacts("Morgan Steam Iron", "MI-2000", null, null,
                null, null, null, null, null, null,
                List.of("superlongword".repeat(30)), null);
        assertThat(renderer.bodyScrollWidth(tenantId, "PAGE_SPEC",
                renderer.specModel(wordy), CANVAS, CANVAS)).isLessThanOrEqualTo(CANVAS);
    }

    @Test
    void htmlInFacts_isEscaped() {
        TemplateFacts sneaky = new TemplateFacts("<b>bold</b> <script>alert(1)</script>",
                null, null, null, null, null, null, null, null, null, null, null);
        String body = bodyOf(renderer.expanded(tenantId, "PAGE_SPEC",
                renderer.specModel(sneaky)));
        assertThat(body).doesNotContain("<b>bold</b>");
        assertThat(body).contains("&lt;b&gt;bold&lt;/b&gt;");
        assertThat(body).doesNotContain("<script>");
    }

    /** The rendered body only — assertions must not scan the base64 fonts. */
    private static String bodyOf(String html) {
        return html.substring(html.indexOf("<body>"));
    }

    private static TemplateFacts facts() {
        return new TemplateFacts("Morgan Steam Iron", "MI-2000", "Home Appliances",
                "300 ml", 2000, "220-240V", "Ceramic soleplate", "Turquoise Blue",
                "1 year",
                List.of("Steam iron", "Measuring cup", "Instruction manual"),
                List.of("Ceramic soleplate", "Vertical steam", "Self-cleaning",
                        "Anti-drip design"),
                List.of("Iron your clothes quickly", "Protect delicate fabrics",
                        "Save time on laundry day", "Look sharp every day"));
    }

    private static byte[] productPng() {
        BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 400);
        g.setColor(new Color(40, 90, 120));
        g.fillRoundRect(150, 80, 300, 240, 40, 40);
        g.setColor(new Color(200, 220, 230));
        g.fillRect(190, 120, 220, 60);
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
