package com.kiano.content.video;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductView;
import com.kiano.content.ads.PriceDisplay;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.asset.PrecheckFlag;
import com.kiano.content.policy.PolicySection;
import com.kiano.content.policy.PolicyService;
import com.kiano.content.template.TemplateEntity;
import com.kiano.content.template.TemplateRegistry;
import com.kiano.content.template.TemplateRenderer;
import com.kiano.content.template.TemplateRenderer.Rect;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.storage.ObjectStorage;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Renders the caption and end-card overlays for a video (C5 T6): every caption
 * is a transparent PNG whose text stays inside the 9:16 safe area - the font
 * scale drops 1.0 → 0.9 → 0.8 before TEXT_OUTSIDE_SAFE_AREA is flagged - and
 * the end card carries the current price, the policy badges and the WhatsApp
 * number from configuration. The template versions are returned for provenance
 * so Task 11 can auto-approve price-only re-renders.
 */
@Component
public class VideoOverlayRenderer {

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;
    private static final double SAFE_TOP = 269;
    private static final double SAFE_BOTTOM = 1248;
    private static final double[] FONT_SCALES = {1.0, 0.9, 0.8};
    private static final String CTA = "Order on WhatsApp";

    /** The rendered PNGs keyed like the EDL overlay inputs plus the price/flags. */
    public record RenderedOverlays(Map<String, byte[]> pngs, PriceDisplay price,
            List<PrecheckFlag> flags, Map<String, Object> templateVersions) {
    }

    /** A re-rendered end card with the current price (C5 T11 price change). */
    public record EndCard(byte[] png, PriceDisplay price, int templateVersion) {
    }

    /**
     * Only the end card, with the current price and the latest END_CARD
     * template (the price re-render keeps every other overlay PNG).
     */
    public EndCard renderEndCard(long tenantId, ProductView product) {
        PriceDisplay price = PriceDisplay.of(product, Instant.now());
        return new EndCard(renderEndCard(tenantId, product, price), price,
                version(tenantId, "END_CARD"));
    }

    private final TemplateRenderer templates;
    private final TemplateRegistry registry;
    private final PolicyService policyService;
    private final AssetMapper assetMapper;
    private final ObjectStorage storage;
    private final VideoProperties videoProperties;

    public VideoOverlayRenderer(TemplateRenderer templates, TemplateRegistry registry,
            PolicyService policyService, AssetMapper assetMapper, ObjectStorage storage,
            VideoProperties videoProperties) {
        this.templates = templates;
        this.registry = registry;
        this.policyService = policyService;
        this.assetMapper = assetMapper;
        this.storage = storage;
        this.videoProperties = videoProperties;
    }

    public RenderedOverlays render(long tenantId, ProductView product, VideoScriptText script,
            int captionCount) {
        List<PrecheckFlag> flags = new ArrayList<>();
        Map<String, byte[]> pngs = new LinkedHashMap<>();
        int n = Math.max(1, captionCount);
        List<String> captions = script.captions() == null ? List.of() : script.captions();
        pngs.put("cap_hook", renderCaption(tenantId, script.hook(), "hook", flags));
        // cap_0 shows the first selling point over the body footage
        pngs.put("cap_0", renderCaption(tenantId, captions.isEmpty() ? "" : captions.get(0),
                "body", flags));
        // cap_1..cap_n split the captions segment; they rotate so the last slot
        // closes on the first caption again
        for (int i = 1; i <= n; i++) {
            String text = captions.isEmpty() ? "" : captions.get(i % captions.size());
            pngs.put("cap_" + i, renderCaption(tenantId, text, "body", flags));
        }
        PriceDisplay price = PriceDisplay.of(product, Instant.now());
        pngs.put("endcard", renderEndCard(tenantId, product, price));
        Map<String, Object> templateVersions = new LinkedHashMap<>();
        templateVersions.put("VIDEO_CAPTION", version(tenantId, "VIDEO_CAPTION"));
        templateVersions.put("END_CARD", version(tenantId, "END_CARD"));
        return new RenderedOverlays(pngs, price, List.copyOf(flags), templateVersions);
    }

    /** Shrinks the font until the caption sits inside [269, 1248], else flags it. */
    private byte[] renderCaption(long tenantId, String text, String position,
            List<PrecheckFlag> flags) {
        for (double scale : FONT_SCALES) {
            Map<String, Object> model = captionModel(text, position, scale);
            if (inSafeArea(templates.textBoxes(tenantId, "VIDEO_CAPTION", model, WIDTH, HEIGHT))) {
                return templates.renderTransparent(tenantId, "VIDEO_CAPTION", model, WIDTH, HEIGHT);
            }
        }
        flags.add(PrecheckFlag.TEXT_OUTSIDE_SAFE_AREA);
        return templates.renderTransparent(tenantId, "VIDEO_CAPTION",
                captionModel(text, position, FONT_SCALES[FONT_SCALES.length - 1]), WIDTH, HEIGHT);
    }

    private static Map<String, Object> captionModel(String text, String position, double scale) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("text", text);
        model.put("position", position);
        model.put("fontScale", scale);
        return model;
    }

    private static boolean inSafeArea(List<Rect> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return false;
        }
        for (Rect box : boxes) {
            if (box.top() < SAFE_TOP || box.bottom() > SAFE_BOTTOM) {
                return false;
            }
        }
        return true;
    }

    private byte[] renderEndCard(long tenantId, ProductView product, PriceDisplay price) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("productName", product.name());
        model.put("productImage", mainImageDataUri(tenantId));
        model.put("current", price.current());
        model.put("strike", price.strike());
        model.put("endsLabel", price.endsLabel());
        List<Map<String, Object>> badges = new ArrayList<>();
        for (Map.Entry<PolicySection, String> entry
                : policyService.requireAdBadges(tenantId).entrySet()) {
            badges.add(Map.of("text", entry.getValue()));
        }
        model.put("badges", badges);
        model.put("cta", CTA);
        String whatsapp = videoProperties.getWhatsappDisplay();
        if (whatsapp != null && !whatsapp.isBlank()) {
            model.put("whatsapp", whatsapp);
        }
        return templates.render(tenantId, "END_CARD", model, WIDTH, HEIGHT);
    }

    /** The latest approved PAGE_MAIN as a JPEG data URI, or empty when none. */
    private String mainImageDataUri(long tenantId) {
        List<AssetEntity> rows = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getSpecCode, "PAGE_MAIN")
                .eq(AssetEntity::getStatus, AssetStatus.APPROVED.name())
                .orderByDesc(AssetEntity::getVersion)
                .last("limit 1"));
        if (rows.isEmpty() || rows.get(0).getObjectKey() == null) {
            return "";
        }
        BufferedImage image = ImageCodec.read(storage.download(rows.get(0).getObjectKey()));
        return "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(ImageCodec.jpeg(image, 0.88f));
    }

    private int version(long tenantId, String code) {
        return registry.latestApproved(tenantId, code)
                .map(TemplateEntity::getVersion).orElse(0);
    }
}
