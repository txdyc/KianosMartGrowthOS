package com.kiano.worker.comfy;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.imaging.CutoutGeometry;
import com.kiano.imaging.ImageCodec;
import com.kiano.imaging.MaskedSsim;
import com.kiano.imaging.SceneCanvasComposer;
import com.kiano.worker.ExecutionContext;
import com.kiano.worker.JobResult;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobPayload;
import com.kiano.workerprotocol.JobStep;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end run of the baseline CUTOUT and SCENE workflows against a real
 * ComfyUI on localhost:8188 (plan Task 12): CUTOUT removes the background of
 * a real product photo, then SCENE inpaints a room around the pasted product
 * on a SceneCanvasComposer canvas. Only runs with KIANO_COMFY_LIVE=1 and a
 * started ComfyUI; excluded from regular builds via the comfy-live tag.
 */
@Tag("comfy-live")
@EnabledIfEnvironmentVariable(named = "KIANO_COMFY_LIVE", matches = "1")
class ComfyLiveIT {

    private static final int ALPHA_THRESHOLD = 127;
    private static final int SSIM_ERODE_PX = 4;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private ComfyProperties properties;

    @TempDir
    Path workDir;

    @BeforeEach
    void setUp() {
        properties = new ComfyProperties();
        properties.setBaseUrl(URI.create("http://127.0.0.1:8188"));
        properties.setTimeout(Duration.ofMinutes(10));
    }

    @Test
    void cutoutThenScene_againstRealComfyUI() throws Exception {
        ComfyUIExecutor executor = new ComfyUIExecutor(new ComfyClient(properties),
                new InputPreprocessor(), new WorkflowBinder(), properties);

        Path photo = workDir.resolve("in/P1.jpg");
        Files.createDirectories(photo.getParent());
        Files.write(photo, resource("/comfy-live/P1.jpg"));
        Path cutoutFile = workDir.resolve("out/cutout.png");
        Files.createDirectories(cutoutFile.getParent());

        JobResult cutout = executor.execute(payload("CUTOUT", Map.of()),
                new ExecutionContext(workDir, Map.of("image", photo),
                        Map.of("cutout", cutoutFile)));

        BufferedImage cutoutImage = ImageCodec.read(Files.readAllBytes(cutoutFile));
        assertThat(CutoutGeometry.alphaCoverage(cutoutImage, ALPHA_THRESHOLD))
                .isBetween(0.05, 0.95);

        SceneCanvasComposer.SceneCanvas canvas =
                SceneCanvasComposer.compose(cutoutImage, 1600, 0.60, 0.12);
        Path canvasFile = workDir.resolve("in/scene-canvas.png");
        Files.write(canvasFile, ImageCodec.png(canvas.image()));
        Path maskFile = workDir.resolve("in/product-mask.png");
        Files.write(maskFile, ImageCodec.png(canvas.productMask()));
        Path sceneFile = workDir.resolve("out/scene.png");

        JobResult scene = executor.execute(payload("SCENE", Map.of(
                "positive", "a cozy Ghanaian home interior with warm daylight, "
                        + "photorealistic product photography, natural light, high detail",
                "negative", "text, letters, watermark, logo, brand name, US plug, "
                        + "European plug, people, hands, deformed, blurry",
                "seed", 42)),
                new ExecutionContext(workDir,
                        Map.of("image", canvasFile, "mask", maskFile),
                        Map.of("image", sceneFile)));

        BufferedImage sceneImage = ImageCodec.read(Files.readAllBytes(sceneFile));
        assertThat(sceneImage.getWidth()).isEqualTo(1600);
        assertThat(sceneImage.getHeight()).isEqualTo(1600);
        assertThat(scene.gpuSeconds()).isPositive();
        assertThat(MaskedSsim.compute(sceneImage, canvas.image(),
                canvas.productMask(), SSIM_ERODE_PX)).isGreaterThanOrEqualTo(0.98);
    }

    private JobPayload payload(String code, Map<String, Object> params) {
        ObjectNode input = mapper.createObjectNode();
        ObjectNode workflow = input.putObject("workflow");
        workflow.put("code", code);
        workflow.put("version", 1);
        workflow.set("json", mapper.readTree(resource("/comfy/" + code + "/v1/workflow.json")));
        workflow.set("manifest", mapper.readTree(resource("/comfy/" + code + "/v1/manifest.json")));
        input.set("params", mapper.valueToTree(params));
        return new JobPayload(1, JobStep.valueOf(code), "main", ExecutorType.COMFYUI, input);
    }

    private static byte[] resource(String path) {
        try (InputStream in = ComfyLiveIT.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(in).readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
