package com.kiano.worker.composite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.imaging.ImageCodec;
import com.kiano.worker.ExecutionContext;
import com.kiano.worker.ExecutorException;
import com.kiano.worker.GenerationExecutor;
import com.kiano.worker.JobResult;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobPayload;
import com.kiano.workerprotocol.JobStep;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * CompositeExecutor: white pages at the contracted occupancy, the scene input
 * canvas with its product mask, and the non-retryable cutout error codes.
 */
class CompositeExecutorTest {

    @TempDir
    Path workDir;

    private final GenerationExecutor executor = new CompositeExecutor();

    @Test
    void whiteMain_writes1600PngWithOccupancy() throws Exception {
        Path cutout = writeCutout(redRectangle(400, 300));
        Path image = workDir.resolve("image.png");
        JobPayload job = payload(JobStep.WHITE_MAIN,
                "{\"composer\":{\"canvas\":1600,\"occupancy\":0.825}}");

        JobResult result = executor.execute(job,
                new ExecutionContext(workDir, Map.of("cutout", cutout), Map.of("image", image)));

        assertThat(result.gpuSeconds()).isZero();
        BufferedImage png = ImageCodec.read(Files.readAllBytes(image));
        assertThat(png.getWidth()).isEqualTo(1600);
        assertThat(png.getHeight()).isEqualTo(1600);
        // White canvas in the corner, the scaled product in the middle.
        assertThat(png.getRGB(10, 10) & 0xFFFFFF).isEqualTo(0xFFFFFF);
        assertThat(png.getRGB(800, 800) & 0xFFFFFF).isEqualTo(0xFF0000);
    }

    @Test
    void sceneInput_writesImageAndMask() throws Exception {
        Path cutout = writeCutout(redRectangle(400, 300));
        Path image = workDir.resolve("image.png");
        Path mask = workDir.resolve("mask.png");
        JobPayload job = payload(JobStep.SCENE_INPUT,
                "{\"placement\":{\"canvas\":800,\"occupancy\":0.60,\"bottomMargin\":0.12}}");

        executor.execute(job, new ExecutionContext(workDir,
                Map.of("cutout", cutout), Map.of("image", image, "mask", mask)));

        BufferedImage scene = ImageCodec.read(Files.readAllBytes(image));
        BufferedImage productMask = ImageCodec.read(Files.readAllBytes(mask));
        assertThat(scene.getWidth()).isEqualTo(800);
        assertThat(productMask.getWidth()).isEqualTo(800);
        // Grey canvas, red product anchored above the 12% bottom margin.
        assertThat(scene.getRGB(10, 10) & 0xFFFFFF).isEqualTo(0x808080);
        assertThat(scene.getRGB(400, 544) & 0xFFFFFF).isEqualTo(0xFF0000);
        // Binary mask: white where the product sits, black elsewhere.
        assertThat(productMask.getRGB(10, 10) & 0xFFFFFF).isEqualTo(0x000000);
        assertThat(productMask.getRGB(400, 544) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    }

    @Test
    void emptyCutout_failsNonRetryableWithCode() throws Exception {
        Path cutout = writeCutout(new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB));
        Path image = workDir.resolve("image.png");
        JobPayload job = payload(JobStep.WHITE_MAIN, "{\"composer\":{\"canvas\":1600,\"occupancy\":0.825}}");

        assertThatThrownBy(() -> executor.execute(job,
                new ExecutionContext(workDir, Map.of("cutout", cutout), Map.of("image", image))))
                .isInstanceOf(ExecutorException.class)
                .hasMessageStartingWith("CUTOUT_EMPTY")
                .satisfies(ex -> assertThat(((ExecutorException) ex).retryable()).isFalse());
    }

    @Test
    void unknownStep_failsNonRetryable() {
        JobPayload job = payload(JobStep.SCENE, "{}");
        assertThatThrownBy(() -> executor.execute(job,
                new ExecutionContext(workDir, Map.of(), Map.of())))
                .isInstanceOf(ExecutorException.class)
                .hasMessageStartingWith("UNSUPPORTED_STEP")
                .satisfies(ex -> assertThat(((ExecutorException) ex).retryable()).isFalse());
    }

    private static JobPayload payload(JobStep step, String inputJson) {
        return new JobPayload(1L, step, "A", ExecutorType.COMPOSITE,
                JsonMapper.builder().build().readTree(inputJson));
    }

    /** A red rectangle inset from the frame on a transparent background. */
    private static BufferedImage redRectangle(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = height / 6; y < height * 5 / 6; y++) {
            for (int x = width / 6; x < width * 5 / 6; x++) {
                image.setRGB(x, y, 0xFFFF0000);
            }
        }
        return image;
    }

    private Path writeCutout(BufferedImage image) throws IOException {
        Path file = workDir.resolve("cutout-" + System.nanoTime() + ".png");
        Files.write(file, ImageCodec.png(image));
        return file;
    }
}
