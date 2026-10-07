package com.kiano.worker.comfy;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.imaging.ImageCodec;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InputPreprocessorTest {

    private final InputPreprocessor preprocessor = new InputPreprocessor();

    @Test
    void preprocess_appliesExifOrientation(@TempDir Path dir) throws Exception {
        Path jpeg = jpegWithOrientation(dir, 4000, 3000,
                TiffTagConstants.ORIENTATION_VALUE_ROTATE_90_CW);

        Path out = preprocessor.prepare(jpeg, null);

        BufferedImage image = ImageCodec.read(Files.readAllBytes(out));
        assertThat(image.getWidth()).isEqualTo(3000);
        assertThat(image.getHeight()).isEqualTo(4000);
    }

    @Test
    void preprocess_downscalesToMaxLongSide(@TempDir Path dir) throws Exception {
        Path png = dir.resolve("photo.png");
        Files.write(png, ImageCodec.png(new BufferedImage(3200, 2400, BufferedImage.TYPE_INT_ARGB)));

        Path out = preprocessor.prepare(png, 2400);

        BufferedImage image = ImageCodec.read(Files.readAllBytes(out));
        assertThat(image.getWidth()).isEqualTo(2400);
        assertThat(image.getHeight()).isEqualTo(1800);
    }

    private static Path jpegWithOrientation(Path dir, int width, int height, int orientation)
            throws Exception {
        byte[] jpeg = ImageCodec.jpeg(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), 0.9f);
        TiffOutputSet exif = new TiffOutputSet();
        exif.getOrCreateRootDirectory()
                .add(TiffTagConstants.TIFF_TAG_ORIENTATION, (short) orientation);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ExifRewriter().updateExifMetadataLossless(jpeg, out, exif);
        Path file = dir.resolve("photo.jpg");
        Files.write(file, out.toByteArray());
        return file;
    }
}
