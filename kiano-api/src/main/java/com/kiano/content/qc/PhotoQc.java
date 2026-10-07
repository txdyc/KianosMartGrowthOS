package com.kiano.content.qc;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

/**
 * JPEG quality control: reads dimensions from the file header, analyses a
 * subsampled grayscale copy (Laplacian variance for sharpness, clipping
 * ratios for exposure) and produces a long-side-400px JPEG thumbnail
 * rotated according to EXIF orientation.
 */
@Component
public class PhotoQc {

    private static final int THUMBNAIL_LONG_SIDE = 400;

    private final QcProperties properties;

    public PhotoQc(QcProperties properties) {
        this.properties = properties;
    }

    public PhotoQcResult evaluate(Path jpeg, boolean applyRules) {
        QcProperties.Photo config = properties.getPhoto();
        try (ImageInputStream input = ImageIO.createImageInputStream(jpeg.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new UnreadableMediaException("No JPEG decoder accepts this file");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int rawWidth = reader.getWidth(0);
                int rawHeight = reader.getHeight(0);
                int orientation = orientation(jpeg);
                boolean rotated = orientation >= 5 && orientation <= 8;
                int width = rotated ? rawHeight : rawWidth;
                int height = rotated ? rawWidth : rawHeight;

                int longSide = Math.max(rawWidth, rawHeight);
                int step = Math.max(1, (int) Math.ceil(longSide / (double) config.getAnalysisLongSide()));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage image = reader.read(0, param);

                Metrics metrics = analyse(image, config);
                List<QcReason> reasons = new ArrayList<>();
                if (applyRules) {
                    if (Math.min(width, height) < config.getMinShortSide()) {
                        reasons.add(QcReason.LOW_RESOLUTION);
                    }
                    if (metrics.blurVariance() < config.getBlurMinVariance()) {
                        reasons.add(QcReason.BLURRY);
                    }
                    if (metrics.overRatio() > config.getMaxOverRatio()) {
                        reasons.add(QcReason.OVEREXPOSED);
                    }
                    if (metrics.underRatio() > config.getMaxUnderRatio()) {
                        reasons.add(QcReason.UNDEREXPOSED);
                    }
                }
                byte[] thumbnail = thumbnail(image, orientation);
                return new PhotoQcResult(width, height, orientation, metrics.blurVariance(), metrics.overRatio(),
                        metrics.underRatio(), List.copyOf(reasons), thumbnail);
            } finally {
                reader.dispose();
            }
        } catch (UnreadableMediaException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new UnreadableMediaException("Cannot decode image: " + ex.getMessage());
        }
    }

    private static int orientation(Path jpeg) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(jpeg.toFile());
            ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory != null && directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                int value = directory.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                return value >= 1 && value <= 8 ? value : 1;
            }
        } catch (Exception ignored) {
            // unreadable EXIF is not fatal; treat as normal orientation
        }
        return 1;
    }

    private record Metrics(double blurVariance, double overRatio, double underRatio) {
    }

    /**
     * Grayscale luminance analysis of the subsampled image: 4-neighbourhood
     * Laplacian variance over interior pixels, and the ratios of pixels at
     * or beyond the clipping thresholds.
     */
    private static Metrics analyse(BufferedImage image, QcProperties.Photo config) {
        int width = image.getWidth();
        int height = image.getHeight();
        double[] gray = new double[width * height];
        long over = 0;
        long under = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                double luminance = 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF)
                        + 0.114 * (rgb & 0xFF);
                gray[y * width + x] = luminance;
                if (luminance >= config.getClipHigh()) {
                    over++;
                }
                if (luminance <= config.getClipLow()) {
                    under++;
                }
            }
        }
        double pixels = width * height;
        double overRatio = pixels == 0 ? 0 : over / pixels;
        double underRatio = pixels == 0 ? 0 : under / pixels;

        double sum = 0;
        double sumSquares = 0;
        long count = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                double centre = gray[y * width + x];
                double laplacian = gray[(y - 1) * width + x] + gray[(y + 1) * width + x]
                        + gray[y * width + x - 1] + gray[y * width + x + 1] - 4 * centre;
                sum += laplacian;
                sumSquares += laplacian * laplacian;
                count++;
            }
        }
        double blurVariance = count == 0 ? 0 : sumSquares / count - (sum / count) * (sum / count);
        return new Metrics(blurVariance, overRatio, underRatio);
    }

    /**
     * Rotates the subsampled image per EXIF orientation, scales to a 400px
     * long side and encodes as JPEG.
     */
    private static byte[] thumbnail(BufferedImage image, int orientation) throws IOException {
        BufferedImage rotated = rotate(image, orientation);
        BufferedImage scaled = scale(rotated);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(scaled, "jpg", out)) {
            throw new UnreadableMediaException("No JPEG writer available for thumbnails");
        }
        return out.toByteArray();
    }

    private static BufferedImage rotate(BufferedImage source, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return source;
        }
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swapped = orientation >= 5;
        int newWidth = swapped ? height : width;
        int newHeight = swapped ? width : height;
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, width, height);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
        BufferedImage target = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.drawImage(source, new AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR), 0, 0);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static BufferedImage scale(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        double factor = (double) THUMBNAIL_LONG_SIDE / Math.max(width, height);
        int newWidth = Math.max(1, (int) Math.round(width * factor));
        int newHeight = Math.max(1, (int) Math.round(height * factor));
        BufferedImage target = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.drawImage(source,
                    new AffineTransformOp(AffineTransform.getScaleInstance(factor, factor),
                            AffineTransformOp.TYPE_BILINEAR),
                    0, 0);
        } finally {
            graphics.dispose();
        }
        return target;
    }
}
