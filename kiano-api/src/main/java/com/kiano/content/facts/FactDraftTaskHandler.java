package com.kiano.content.facts;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.media.SourceMediaEntity;
import com.kiano.content.media.SourceMediaMapper;
import com.kiano.content.facts.FactSheetService.FactSheetView;
import com.kiano.content.template.TemplateRegistry;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.llm.LlmException;
import com.kiano.platform.llm.LlmGateway;
import com.kiano.platform.llm.LlmImage;
import com.kiano.platform.llm.LlmPurpose;
import com.kiano.platform.llm.LlmRefusedException;
import com.kiano.platform.llm.LlmRequest;
import com.kiano.platform.llm.LlmResult;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import com.kiano.platform.queue.TaskHandler;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.tiff.TiffField;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Queue handler for FACT_DRAFT: reads the accepted P5 (rating plate) and
 * PROMO images, EXIF-orients and downscales them to a 1568px long side,
 * sends them to Claude with the FACT_PROMPT template and effort HIGH, then
 * persists the parsed result as a new fact DRAFT (G2). Refusals and
 * non-retryable gateway errors fail the task immediately so the UI can ask
 * for manual entry; retryable errors are left to the queue backoff.
 */
@Component
public class FactDraftTaskHandler implements TaskHandler {

    public static final String TYPE = "FACT_DRAFT";
    private static final int MAX_IMAGE_LONG_SIDE = 1568;
    private static final float IMAGE_QUALITY = 0.9f;
    private static final int USER_TEXT_TRUNCATION = 4000;

    private final SourceMediaMapper sourceMediaMapper;
    private final ObjectStorage storage;
    private final LlmGateway gateway;
    private final FactSheetService factSheetService;
    private final TemplateRegistry templates;
    private final ProductCatalog productCatalog;

    public FactDraftTaskHandler(SourceMediaMapper sourceMediaMapper, ObjectStorage storage,
            LlmGateway gateway, FactSheetService factSheetService, TemplateRegistry templates,
            ProductCatalog productCatalog) {
        this.sourceMediaMapper = sourceMediaMapper;
        this.storage = storage;
        this.gateway = gateway;
        this.factSheetService = factSheetService;
        this.templates = templates;
        this.productCatalog = productCatalog;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object handle(TaskContext ctx) throws Exception {
        long tenantId = ctx.tenantId();
        long productId = ctx.payload().path("productId").asLong();
        List<MediaPair> sources = acceptedSources(tenantId, productId);
        String system = templates.latestApproved(tenantId, "FACT_PROMPT")
                .map(template -> template.getBody())
                .orElseThrow(() -> new NonRetryableTaskException(
                        "TEMPLATE_NOT_FOUND: no approved FACT_PROMPT template"));
        List<LlmImage> images = sources.stream()
                .map(pair -> prepare(pair.entity(), pair.label()))
                .toList();
        LlmRequest<FactDraftResult> request = new LlmRequest<>(tenantId, LlmPurpose.FACT_DRAFT,
                system, userText(tenantId, productId), images, FactDraftResult.class,
                LlmRequest.Effort.HIGH, 16000);
        try {
            LlmResult<FactDraftResult> result = gateway.complete(request);
            FactDraftResult draft = result.output();
            if (draft == null || draft.facts() == null) {
                throw new NonRetryableTaskException(
                        "LLM_INVALID_OUTPUT: Claude returned no fact draft");
            }
            List<Long> mediaIds = sources.stream().map(pair -> pair.entity().getId()).toList();
            FactSheetView view = factSheetService.createDraftFromLlm(tenantId, productId,
                    draft.facts(), draft.sources() == null ? Map.of() : draft.sources(),
                    mediaIds, result.llmCallId(),
                    draft.unreadable() == null ? List.of() : draft.unreadable());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("factSheetId", view.id());
            out.put("version", view.version());
            out.put("llmCallId", result.llmCallId());
            return out;
        } catch (LlmRefusedException ex) {
            throw new NonRetryableTaskException(
                    "LLM_REFUSED" + (ex.category() == null ? "" : " (" + ex.category() + ")")
                            + ": " + ex.getMessage());
        } catch (LlmException ex) {
            if (!ex.retryable()) {
                throw new NonRetryableTaskException(ex.code() + ": " + ex.getMessage());
            }
            throw ex;
        }
    }

    /** The latest accepted P5 and PROMO media; both missing is a hard failure. */
    private List<MediaPair> acceptedSources(long tenantId, long productId) {
        List<MediaPair> pairs = new ArrayList<>();
        for (String shotCode : List.of("P5", "PROMO")) {
            List<SourceMediaEntity> media = sourceMediaMapper.selectList(
                    Wrappers.<SourceMediaEntity>lambdaQuery()
                            .eq(SourceMediaEntity::getTenantId, tenantId)
                            .eq(SourceMediaEntity::getProductId, productId)
                            .eq(SourceMediaEntity::getShotCode, shotCode)
                            .eq(SourceMediaEntity::getStatus, "ACCEPTED")
                            .orderByDesc(SourceMediaEntity::getUploadedAt)
                            .last("limit 1"));
            if (!media.isEmpty()) {
                pairs.add(new MediaPair(media.get(0),
                        shotCode.equals("P5") ? "P5 rating plate" : "PROMO marketing image"));
            }
        }
        if (pairs.isEmpty()) {
            throw new NonRetryableTaskException(
                    "NO_FACT_SOURCES: no accepted P5 or PROMO media for this product");
        }
        return pairs;
    }

    /** EXIF-orients, scales the long side to 1568 and encodes JPEG quality 90. */
    private LlmImage prepare(SourceMediaEntity media, String label) {
        byte[] bytes = storage.download(media.getObjectKey());
        int orientation = readJpegOrientation(bytes);
        BufferedImage image = orient(ImageCodec.read(bytes), orientation);
        image = downscale(image, MAX_IMAGE_LONG_SIDE);
        return new LlmImage(ImageCodec.jpeg(image, IMAGE_QUALITY), label);
    }

    private static BufferedImage downscale(BufferedImage image, int maxLongSide) {
        int longSide = Math.max(image.getWidth(), image.getHeight());
        if (longSide <= maxLongSide) {
            return image;
        }
        double scale = (double) maxLongSide / longSide;
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(image, 0, 0, w, h, null);
        g.dispose();
        return scaled;
    }

    private String userText(long tenantId, long productId) {
        Optional<ProductView> product = productCatalog.findById(tenantId, productId);
        if (product.isEmpty()) {
            throw new NonRetryableTaskException("NOT_FOUND: product " + productId + " not found");
        }
        ProductView view = product.get();
        StringBuilder text = new StringBuilder();
        text.append("Product name: ").append(trim(view.name(), 200)).append('\n');
        if (!view.categorySlugs().isEmpty()) {
            text.append("WooCommerce category: ")
                    .append(String.join(", ", view.categorySlugs())).append('\n');
        }
        text.append('\n')
                .append("Transcribe the specification values from the photos. The products "
                        + "existing WooCommerce text contains no description; treat the name "
                        + "and category above as WOO_TEXT context only.");
        String content = text.toString();
        return content.length() <= USER_TEXT_TRUNCATION ? content
                : content.substring(0, USER_TEXT_TRUNCATION);
    }

    private static String trim(String value, int max) {
        return value == null ? "" : (value.length() <= max ? value : value.substring(0, max));
    }

    private static int readJpegOrientation(byte[] bytes) {
        try {
            ImageMetadata metadata = Imaging.getMetadata(bytes);
            if (metadata instanceof JpegImageMetadata jpeg) {
                TiffField orientation = jpeg
                        .findExifValue(TiffTagConstants.TIFF_TAG_ORIENTATION);
                if (orientation != null) {
                    return orientation.getIntValue();
                }
            }
        } catch (Exception ignored) {
            // broken/absent EXIF → treat as orientation 1
        }
        return 1;
    }

    private static BufferedImage orient(BufferedImage image, int orientation) {
        int width = image.getWidth();
        int height = image.getHeight();
        int outWidth = orientation >= 5 ? height : width;
        int outHeight = orientation >= 5 ? width : height;
        BufferedImage oriented = new BufferedImage(outWidth, outHeight,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = oriented.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
        g.drawImage(image, transform, null);
        g.dispose();
        return oriented;
    }

    private record MediaPair(SourceMediaEntity entity, String label) {
    }
}