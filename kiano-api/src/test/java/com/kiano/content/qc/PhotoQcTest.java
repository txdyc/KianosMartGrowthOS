package com.kiano.content.qc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.kiano.content.qc.PhotoQcTestImages.Checkerboard;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PhotoQc on synthesized images: sharpness via Laplacian variance, exposure
 * clipping ratios, display dimensions after EXIF rotation and thumbnail
 * generation. Images use near-black/near-white cells so a healthy
 * checkerboard stays below the clipping ratios.
 */
class PhotoQcTest {

    private final PhotoQc qc = new PhotoQc(new QcProperties());

    @TempDir
    Path tempDir;

    @Test
    void sharpCheckerboard_4000x3000_accepted() throws IOException {
        Path file = tempDir.resolve("MG-BL200_P5.jpg");
        PhotoQcTestImages.writeJpeg(new Checkerboard(4000, 3000), file);

        PhotoQcResult result = qc.evaluate(file, true);

        assertThat(result.width()).isEqualTo(4000);
        assertThat(result.height()).isEqualTo(3000);
        assertThat(result.exifOrientation()).isEqualTo(1);
        assertThat(result.blurVariance()).isGreaterThan(60);
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void boxBlurred_isBlurry() throws IOException {
        Path file = tempDir.resolve("MG-BL200_P5.jpg");
        PhotoQcTestImages.writeJpeg(PhotoQcTestImages.boxBlur(new Checkerboard(4000, 3000), 15), file);

        PhotoQcResult result = qc.evaluate(file, true);

        assertThat(result.reasons()).containsExactly(QcReason.BLURRY);
    }

    @Test
    void lowResolution_1500x1000() throws IOException {
        Path file = tempDir.resolve("MG-BL200_P5.jpg");
        PhotoQcTestImages.writeJpeg(new Checkerboard(1500, 1000), file);

        PhotoQcResult result = qc.evaluate(file, true);

        assertThat(result.reasons()).containsExactly(QcReason.LOW_RESOLUTION);
    }

    @Test
    void allWhite_overexposed_allBlack_underexposed() throws IOException {
        Path white = tempDir.resolve("white.jpg");
        PhotoQcTestImages.writeJpeg(PhotoQcTestImages.solid(4000, 3000, 255), white);
        PhotoQcResult whiteResult = qc.evaluate(white, true);
        assertThat(whiteResult.overRatio()).isGreaterThan(0.30);
        assertThat(whiteResult.reasons()).contains(QcReason.OVEREXPOSED);

        Path black = tempDir.resolve("black.jpg");
        PhotoQcTestImages.writeJpeg(PhotoQcTestImages.solid(4000, 3000, 0), black);
        PhotoQcResult blackResult = qc.evaluate(black, true);
        assertThat(blackResult.underRatio()).isGreaterThan(0.30);
        assertThat(blackResult.reasons()).contains(QcReason.UNDEREXPOSED);
    }

    @Test
    void exifOrientation6_reportsPortraitAndPortraitThumb() throws Exception {
        Path file = tempDir.resolve("MG-BL200_P5.jpg");
        PhotoQcTestImages.writeJpeg(new Checkerboard(4000, 3000), file);
        addExifOrientation(file, (short) 6);

        PhotoQcResult result = qc.evaluate(file, true);

        assertThat(result.exifOrientation()).isEqualTo(6);
        assertThat(result.width()).isEqualTo(3000);
        assertThat(result.height()).isEqualTo(4000);

        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(result.thumbnailJpeg()));
        assertThat(thumb.getHeight()).isGreaterThan(thumb.getWidth());
        assertThat(Math.max(thumb.getWidth(), thumb.getHeight())).isEqualTo(400);
    }

    @Test
    void promo_noRulesApplied() throws IOException {
        Path file = tempDir.resolve("MG-BL200_PROMO.jpg");
        PhotoQcTestImages.writeJpeg(new Checkerboard(1000, 1000), file);

        PhotoQcResult result = qc.evaluate(file, false);

        assertThat(result.width()).isEqualTo(1000);
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void garbageBytes_throwUnreadable() throws IOException {
        Path file = tempDir.resolve("MG-BL200_P5.jpg");
        Files.writeString(file, "definitely not a jpeg ".repeat(50), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> qc.evaluate(file, true))
                .isInstanceOf(UnreadableMediaException.class);
    }

    /**
     * Sanity check that the EXIF we write with commons-imaging is the one
     * metadata-extractor reads back (the production code reads it with
     * metadata-extractor's ExifIFD0Directory).
     */
    @Test
    void exifRewrite_isReadableByMetadataExtractor() throws Exception {
        Path file = tempDir.resolve("MG-BL200_P5.jpg");
        PhotoQcTestImages.writeJpeg(new Checkerboard(400, 300), file);
        addExifOrientation(file, (short) 6);

        Metadata metadata = com.drew.imaging.ImageMetadataReader.readMetadata(file.toFile());
        ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        assertThat(directory).isNotNull();
        assertThat(directory.getInt(ExifIFD0Directory.TAG_ORIENTATION)).isEqualTo(6);
    }

    private static void addExifOrientation(Path file, short orientation) throws Exception {
        TiffOutputSet outputSet = new TiffOutputSet();
        outputSet.getOrCreateRootDirectory().add(TiffTagConstants.TIFF_TAG_ORIENTATION, orientation);
        byte[] jpeg = Files.readAllBytes(file);
        try (OutputStream out = Files.newOutputStream(file)) {
            new ExifRewriter().updateExifMetadataLossless(jpeg, out, outputSet);
        }
    }
}
