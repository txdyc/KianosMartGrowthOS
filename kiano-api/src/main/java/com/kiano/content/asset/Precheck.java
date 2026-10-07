package com.kiano.content.asset;

import com.kiano.imaging.MaskedSsim;
import com.kiano.imaging.WhiteChecks;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Automatic prechecks for generated pages. White pages are code-composited
 * (no AI involved) so they get no OCR; scene pages are compared against the
 * SCENE_INPUT composite via masked SSIM and OCR-checked for AI text outside
 * the product area.
 */
@Component
public class Precheck {

    private static final int EDGE_BORDER_PX = 2;
    private static final int OCCUPANCY_THRESHOLD = 8;
    private static final int SSIM_ERODE_PX = 2;
    private static final int AI_TEXT_MIN_ALNUM = 3;
    private static final double AI_TEXT_MIN_CONFIDENCE = 60;
    private static final int MASK_GRAY = 0xFF808080;

    private final PrecheckProperties properties;
    private final TextDetector detector;

    public Precheck(PrecheckProperties properties, TextDetector detector) {
        this.properties = properties;
        this.detector = detector;
    }

    /**
     * White-background checks: pure-white edges plus (for WHITE pages)
     * product occupancy within [min, max]. INBOX pages only check edges.
     */
    public PrecheckResult white(BufferedImage image, boolean checkOccupancy) {
        List<PrecheckFlag> flags = new ArrayList<>();
        if (!WhiteChecks.edgesPureWhite(image, EDGE_BORDER_PX)) {
            flags.add(PrecheckFlag.EDGE_NOT_WHITE);
        }
        double occupancy = WhiteChecks.occupancy(image, OCCUPANCY_THRESHOLD);
        if (checkOccupancy
                && (occupancy < properties.getMinOccupancy() || occupancy > properties.getMaxOccupancy())) {
            flags.add(PrecheckFlag.OCCUPANCY_OUT_OF_RANGE);
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("ssim", null);
        metrics.put("occupancy", round4(occupancy));
        metrics.put("ocrWords", 0);
        metrics.put("ocr", "SKIPPED");
        return new PrecheckResult(List.copyOf(flags), metrics);
    }

    /** Scene checks: masked SSIM against the composite input, then OCR for AI text. */
    public PrecheckResult scene(BufferedImage output, BufferedImage sceneInput,
            BufferedImage productMask) {
        List<PrecheckFlag> flags = new ArrayList<>();
        double ssim = MaskedSsim.compute(output, sceneInput, productMask, SSIM_ERODE_PX);
        if (Double.isNaN(ssim) || ssim < properties.getSsimMin()) {
            flags.add(PrecheckFlag.PRODUCT_MISMATCH);
        }
        int ocrWords = 0;
        String ocr = "SKIPPED";
        if (detector.enabled()) {
            try {
                List<TextDetector.OcrWord> words = detectOutsideProduct(output, productMask);
                ocr = "OK";
                ocrWords = words.size();
                if (words.stream().anyMatch(Precheck::suspicious)) {
                    flags.add(PrecheckFlag.AI_TEXT);
                }
            } catch (IOException ex) {
                ocr = "SKIPPED"; // OCR failure never blocks asset creation
            }
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("ssim", Double.isNaN(ssim) ? null : round4(ssim));
        metrics.put("occupancy", null);
        metrics.put("ocrWords", ocrWords);
        metrics.put("ocr", ocr);
        return new PrecheckResult(List.copyOf(flags), metrics);
    }

    /** Greys out the product area and OCRs the rest of the image. */
    private List<TextDetector.OcrWord> detectOutsideProduct(BufferedImage output,
            BufferedImage productMask) throws IOException {
        BufferedImage masked = new BufferedImage(output.getWidth(), output.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                boolean inProduct = (productMask.getRGB(x, y) & 0xFF) >= 128;
                masked.setRGB(x, y, inProduct ? MASK_GRAY : output.getRGB(x, y));
            }
        }
        Path png = Files.createTempFile("kiano-precheck", ".png");
        try {
            ImageIO.write(masked, "png", png.toFile());
            return detector.detect(png);
        } finally {
            Files.deleteIfExists(png);
        }
    }

    private static boolean suspicious(TextDetector.OcrWord word) {
        long alnum = word.text().chars().filter(Character::isLetterOrDigit).count();
        return alnum >= AI_TEXT_MIN_ALNUM && word.confidence() >= AI_TEXT_MIN_CONFIDENCE;
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
