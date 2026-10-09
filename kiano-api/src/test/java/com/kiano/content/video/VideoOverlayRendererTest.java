package com.kiano.content.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.commerce.ProductView;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.asset.PrecheckFlag;
import com.kiano.content.policy.PolicySection;
import com.kiano.content.policy.PolicyService;
import com.kiano.content.policy.PolicyService.SectionText;
import com.kiano.content.template.TemplateBootstrap;
import com.kiano.content.template.TemplateRenderer;
import com.kiano.imaging.ImageCodec;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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
 * VideoOverlayRenderer (C5 T6): caption and end-card PNGs match the goldens
 * (regenerate with -Dkiano.golden.update=true), captions are transparent
 * outside the text, long captions shrink or get flagged, script text is
 * HTML-escaped, and the WhatsApp number is hidden when not configured.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class VideoOverlayRendererTest {

    private static final int GRAY_TOLERANCE = 32;
    private static final double MAX_DIFF_RATIO = 0.005;
    private static final String GOLDEN_DIR = "src/test/resources/golden/video";

    @Autowired
    private VideoOverlayRenderer renderer;

    @Autowired
    private TemplateRenderer templates;

    @Autowired
    private TemplateBootstrap bootstrap;

    @Autowired
    private PolicyService policyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.kiano.platform.storage.ObjectStorage storage;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private long tenantId;
    private long storeId;
    private long productId;
    private byte[] jpeg;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from llm_call");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store_policy");
        jdbcTemplate.update("delete from template");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        bootstrap.bootstrapAll();
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, "
                        + "regular_price, sale_price, price, sale_from_at, sale_to_at, status, synced_at) "
                        + "values (?, ?, -9001, 'simple', 'MG-KTL17', 'Morgan 1.7L Kettle', 299, 249, 249, "
                        + "now() - interval '1 day', now() + interval '3 days', 'publish', now()) returning id",
                Long.class, tenantId, storeId);
        long userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'video-overlay@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        var user = new com.kiano.platform.auth.CurrentUser(userId, tenantId,
                com.kiano.platform.auth.Role.OPERATOR, "video-overlay@example.test");
        policyService.save(user, sectionsWithBadges());
        jpeg = ImageCodec.jpeg(productImage(), 0.9f);
        String key = "t" + tenantId + "/assets/" + productId + "/PAGE_MAIN/main/v1.jpg";
        storage.put(key, jpeg, "image/jpeg");
        jdbcTemplate.update(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "object_key, thumb_object_key, width, height, status, precheck_json, provenance_json) "
                        + "values (?, ?, 'PAGE_MAIN', 'main', 1, 'IMAGE', ?, ?, 1080, 1080, ?, "
                        + "'{}'::jsonb, '{}'::jsonb)",
                tenantId, productId, key, key + "_thumb.jpg", AssetStatus.APPROVED.name());
    }

    @Test
    void captionsMatchGolden_andAreTransparentOutsideText() throws Exception {
        VideoScriptText script = new VideoScriptText("See it steam in seconds",
                List.of("Auto shut-off", "1.7 L stainless steel", "Fast heating"));

        VideoOverlayRenderer.RenderedOverlays overlays = renderer.render(tenantId,
                saleProduct(), script, 3);

        assertThat(overlays.pngs()).containsKeys("cap_hook", "cap_0", "cap_1", "cap_2", "cap_3",
                "endcard");
        assertMatchesGolden(Path.of(GOLDEN_DIR, "caption-hook.png"), overlays.pngs().get("cap_hook"));
        assertMatchesGolden(Path.of(GOLDEN_DIR, "caption-body.png"), overlays.pngs().get("cap_0"));
        assertThat(overlays.flags()).isEmpty();

        BufferedImage hook = ImageCodec.read(overlays.pngs().get("cap_hook"));
        assertThat(alpha(hook, 0, 0)).isZero();
        assertThat(alpha(hook, 1079, 0)).isZero();
        assertThat(alpha(hook, 0, 1919)).isZero();
        assertThat(alpha(hook, 1079, 1919)).isZero();
        BufferedImage body = ImageCodec.read(overlays.pngs().get("cap_0"));
        assertThat(alpha(body, 0, 0)).isZero();
        assertThat(alpha(body, 1079, 1919)).isZero();
    }

    @Test
    void endCardMatchesGolden_withAndWithoutSale() throws Exception {
        VideoScriptText script = new VideoScriptText("Hook", List.of("a", "b", "c"));

        VideoOverlayRenderer.RenderedOverlays onSale = renderer.render(tenantId,
                saleProduct(), script, 3);
        assertMatchesGolden(Path.of(GOLDEN_DIR, "endcard-sale.png"),
                onSale.pngs().get("endcard"));
        assertThat(onSale.price().current()).isEqualTo("GH₵ 249");
        assertThat(onSale.price().strike()).isEqualTo("GH₵ 299");

        VideoOverlayRenderer.RenderedOverlays noSale = renderer.render(tenantId,
                noSaleProduct(), script, 3);
        assertMatchesGolden(Path.of(GOLDEN_DIR, "endcard.png"), noSale.pngs().get("endcard"));
        assertThat(noSale.price().strike()).isNull();
        assertThat(noSale.price().current()).isEqualTo("GH₵ 299");
    }

    @Test
    void longCaption_staysInsideSafeArea_orFlagged() throws Exception {
        VideoScriptText longHook = new VideoScriptText(
                "x".repeat(60), List.of("Auto shut-off", "1.7 L stainless steel", "Fast heating"));

        VideoOverlayRenderer.RenderedOverlays overlays = renderer.render(tenantId,
                saleProduct(), longHook, 3);

        // either the font shrank enough to stay inside the safe area, or it is flagged
        assertThat(overlays.flags()).allMatch(
                flag -> flag == PrecheckFlag.TEXT_OUTSIDE_SAFE_AREA);
    }

    @Test
    void escapesScriptText() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("text", "<b>x</b>");
        model.put("position", "hook");
        model.put("fontScale", 1);

        String html = templates.expanded(tenantId, "VIDEO_CAPTION", model);

        assertThat(html).contains("&lt;b&gt;x&lt;/b&gt;");
        assertThat(html).doesNotContain("<b>x</b>");
    }

    @Test
    void endCard_noWhatsappConfigured_hidesNumber() {
        // whatsapp-display defaults to empty → the end card shows no number
        String html = endCardHtml(saleProduct());
        assertThat(html).doesNotContain("class=\"whatsapp\"");

        Map<String, Object> model = endCardModel(saleProduct());
        model.put("whatsapp", "+233 55 123 4567");
        String withNumber = templates.expanded(tenantId, "END_CARD", model);
        assertThat(withNumber).contains("class=\"whatsapp\"").contains("+233 55 123 4567");
    }

    private String endCardHtml(ProductView product) {
        return templates.expanded(tenantId, "END_CARD", endCardModel(product));
    }

    private Map<String, Object> endCardModel(ProductView product) {
        var price = com.kiano.content.ads.PriceDisplay.of(product, Instant.now());
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("productName", product.name());
        model.put("productImage", "");
        model.put("current", price.current());
        model.put("strike", price.strike());
        model.put("endsLabel", price.endsLabel());
        model.put("badges", List.of(Map.of("text", "COD"), Map.of("text", "MTN MoMo"),
                Map.of("text", "Fast delivery")));
        model.put("cta", "Order on WhatsApp");
        return model;
    }

    private ProductView saleProduct() {
        return new ProductView(productId, null, "simple", "MG-KTL17", "Morgan 1.7L Kettle",
                BigDecimal.valueOf(299), BigDecimal.valueOf(249), BigDecimal.valueOf(249), null,
                "instock", "publish", null, List.of(), Instant.now().minusSeconds(86400),
                Instant.now().plusSeconds(259200));
    }

    private ProductView noSaleProduct() {
        return new ProductView(productId, null, "simple", "MG-KTL17", "Morgan 1.7L Kettle",
                BigDecimal.valueOf(299), null, BigDecimal.valueOf(299), null,
                "instock", "publish", null, List.of(), null, null);
    }

    private static Map<PolicySection, SectionText> sectionsWithBadges() {
        Map<PolicySection, SectionText> sections = new EnumMap<>(PolicySection.class);
        sections.put(PolicySection.DELIVERY, new SectionText("Delivery", "1-3 days", "Fast delivery"));
        sections.put(PolicySection.COD, new SectionText("COD", "Pay on receipt", "COD"));
        sections.put(PolicySection.MOMO, new SectionText("MoMo", "MTN MoMo", "MTN MoMo"));
        sections.put(PolicySection.WARRANTY, new SectionText("Warranty", "1 year", null));
        sections.put(PolicySection.RETURNS, new SectionText("Returns", "7 days", null));
        return sections;
    }

    private static BufferedImage productImage() {
        BufferedImage img = new BufferedImage(1080, 1080, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1080, 1080);
        g.setColor(new Color(180, 60, 40));
        g.fillRoundRect(200, 180, 680, 720, 40, 40);
        g.setColor(new Color(230, 230, 230));
        g.fillRect(260, 300, 560, 140);
        g.dispose();
        return img;
    }

    private static int alpha(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) >>> 24 & 0xFF;
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
