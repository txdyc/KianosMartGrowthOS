package com.kiano.worker.comfy;

import com.kiano.imaging.ImageCodec;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.tiff.TiffField;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Normalises an image before it goes to ComfyUI: applies the EXIF
 * orientation so the pixels are upright, downscales to the manifest's
 * maxLongSide and re-encodes as PNG. Images that need neither are
 * passed through untouched.
 */
@Component
@Profile("worker")
public class InputPreprocessor {

    public Path prepare(Path in, @Nullable Integer maxLongSide) throws IOException {
        byte[] bytes = Files.readAllBytes(in);
        int orientation = readOrientation(bytes);
        if (orientation == 1 && maxLongSide == null) {
            return in;
        }
        BufferedImage image = orient(ImageCodec.read(bytes), orientation);
        image = downscale(image, maxLongSide);
        Path out = in.resolveSibling(in.getFileName().toString() + ".prepared.png");
        Files.write(out, ImageCodec.png(image));
        return out;
    }

    /** EXIF orientation of a JPEG, or 1 when there is no readable EXIF. */
    private static int readOrientation(byte[] bytes) {
        try {
            ImageMetadata metadata = Imaging.getMetadata(bytes);
            if (metadata instanceof JpegImageMetadata jpeg) {
                TiffField orientation = jpeg.findExifValue(TiffTagConstants.TIFF_TAG_ORIENTATION);
                if (orientation != null) {
                    return orientation.getIntValue();
                }
            }
        } catch (IOException ex) {
            // absent or broken EXIF → normal orientation
        }
        return 1;
    }

    /** EXIF orientations 2–8 as affine transforms (canvas swaps sides for 5–8). */
    private static BufferedImage orient(BufferedImage image, int orientation) {
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, image.getWidth(), 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, image.getWidth(), image.getHeight());
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, image.getHeight());
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, image.getHeight(), 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, image.getHeight(), image.getWidth());
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, image.getWidth());
            default -> null;
        };
        if (transform == null) {
            return image;
        }
        boolean swapped = orientation >= 5;
        int width = swapped ? image.getHeight() : image.getWidth();
        int height = swapped ? image.getWidth() : image.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, transform, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage downscale(BufferedImage image, @Nullable Integer maxLongSide) {
        if (maxLongSide == null) {
            return image;
        }
        int longEdge = Math.max(image.getWidth(), image.getHeight());
        if (longEdge <= maxLongSide) {
            return image;
        }
        double scale = (double) maxLongSide / longEdge;
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
