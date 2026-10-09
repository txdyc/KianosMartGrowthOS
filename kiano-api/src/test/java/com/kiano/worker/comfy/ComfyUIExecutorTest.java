package com.kiano.worker.comfy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.kiano.imaging.ImageCodec;
import com.kiano.worker.ExecutionContext;
import com.kiano.worker.ExecutorException;
import com.kiano.worker.ExecutorUnavailableException;
import com.kiano.worker.JobResult;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobPayload;
import com.kiano.workerprotocol.JobStep;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WireMock-backed replay of the ComfyUI HTTP API: upload, queue, poll, view.
 * Failure classes (unreachable, 5xx, node errors, execution errors, timeout)
 * map onto the executor's retryable/unavailable contract.
 */
class ComfyUIExecutorTest {

    private static final String HISTORY = "/history/it-prompt-1";

    private final JsonMapper mapper = JsonMapper.builder().build();

    private WireMockServer comfy;
    private ComfyProperties properties;

    @TempDir
    Path workDir;

    @BeforeEach
    void startComfy() {
        comfy = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        comfy.start();
        properties = new ComfyProperties();
        properties.setBaseUrl(URI.create(comfy.baseUrl()));
        properties.setTimeout(Duration.ofMinutes(15));
    }

    @AfterEach
    void stopComfy() {
        comfy.stop();
    }

    @Test
    void success_uploadsQueuesPollsAndDownloadsOutput() throws Exception {
        stubObjectInfo();
        comfy.stubFor(post(urlPathEqualTo("/upload/image"))
                .willReturn(okJson("{\"name\": \"photo.png\", \"subfolder\": \"kiano\", \"type\": \"input\"}")));
        comfy.stubFor(post(urlPathEqualTo("/prompt"))
                .willReturn(okJson(fixture("prompt-ok.json"))));
        comfy.stubFor(get(urlPathEqualTo(HISTORY))
                .inScenario("history").whenScenarioStateIs(Scenario.STARTED).willSetStateTo("done")
                .willReturn(okJson(fixture("history-running.json"))));
        comfy.stubFor(get(urlPathEqualTo(HISTORY))
                .inScenario("history").whenScenarioStateIs("done")
                .willReturn(okJson(fixture("history-success.json"))));
        byte[] cutoutPng = ImageCodec.png(new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB));
        comfy.stubFor(get(urlPathEqualTo("/view"))
                .withQueryParam("filename", equalTo("cutout_00009_.png"))
                .withQueryParam("subfolder", equalTo("kiano"))
                .withQueryParam("type", equalTo("output"))
                .willReturn(aResponse().withBody(cutoutPng)));

        JobResult result = executor()
                .execute(payload("[\"LoadImage\", \"SaveImage\"]"), context());

        assertThat(Files.readAllBytes(workDir.resolve("out/cutout.png"))).isEqualTo(cutoutPng);
        assertThat(result.gpuSeconds()).isCloseTo(0.18, within(1e-6));
        comfy.verify(1, postRequestedFor(urlPathEqualTo("/upload/image")));
        comfy.verify(1, postRequestedFor(urlPathEqualTo("/prompt")));
        comfy.verify(1, getRequestedFor(urlPathEqualTo("/view")));
        JsonNode queued = mapper.readTree(
                comfy.findAll(postRequestedFor(urlPathEqualTo("/prompt"))).get(0).getBodyAsString());
        assertThat(queued.path("prompt").path("1").path("inputs").path("image").asString())
                .isEqualTo("kiano/photo.png");
        assertThat(queued.path("client_id").asString()).isNotEmpty();
    }

    @Test
    void nodeErrors_nonRetryable() throws Exception {
        stubUploadAndPromptOk();
        comfy.stubFor(post(urlPathEqualTo("/prompt"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody(fixture("prompt-node-errors.json"))));

        assertThatThrownBy(() -> executor().execute(payload("null"), context()))
                .isInstanceOfSatisfying(ExecutorException.class, ex -> {
                    assertThat(ex.retryable()).isFalse();
                    assertThat(ex.getMessage()).contains("node_errors");
                });
    }

    @Test
    void missingNodeClass_nonRetryable() throws Exception {
        stubObjectInfo();

        assertThatThrownBy(() -> executor()
                .execute(payload("[\"LoadImage\", \"INFLUX_KIANO_NODE\"]"), context()))
                .isInstanceOfSatisfying(ExecutorException.class, ex -> {
                    assertThat(ex.retryable()).isFalse();
                    assertThat(ex.getMessage())
                            .contains("ComfyUI is missing node classes")
                            .contains("INFLUX_KIANO_NODE");
                });
        comfy.verify(0, postRequestedFor(urlPathEqualTo("/prompt")));
    }

    @Test
    void connectionRefused_executorUnavailable() throws Exception {
        WireMockServer dead = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        dead.start();
        String deadUrl = dead.baseUrl();
        dead.stop();
        properties.setBaseUrl(URI.create(deadUrl));

        assertThatThrownBy(() -> executor().execute(payload("null"), context()))
                .isInstanceOf(ExecutorUnavailableException.class);
    }

    @Test
    void http500_retryable() throws Exception {
        comfy.stubFor(post(urlPathEqualTo("/upload/image"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> executor().execute(payload("null"), context()))
                .isInstanceOfSatisfying(ExecutorException.class,
                        ex -> assertThat(ex.retryable()).isTrue());
    }

    @Test
    void executionError_retryableWithMessage() throws Exception {
        stubUploadAndPromptOk();
        comfy.stubFor(get(urlPathEqualTo(HISTORY))
                .willReturn(okJson(fixture("history-error.json"))));

        assertThatThrownBy(() -> executor().execute(payload("null"), context()))
                .isInstanceOfSatisfying(ExecutorException.class, ex -> {
                    assertThat(ex.retryable()).isTrue();
                    assertThat(ex.getMessage()).contains("CUDA out of memory");
                });
    }

    @Test
    void timeout_interruptsAndRetryable() throws Exception {
        properties.setTimeout(Duration.ofSeconds(2));
        stubUploadAndPromptOk();
        // /history stays unstubbed → WireMock's 404 = "not finished yet".

        assertThatThrownBy(() -> executor().execute(payload("null"), context()))
                .isInstanceOfSatisfying(ExecutorException.class,
                        ex -> assertThat(ex.retryable()).isTrue());
        comfy.verify(1, postRequestedFor(urlPathEqualTo("/interrupt")));
    }

    private ComfyUIExecutor executor() {
        return new ComfyUIExecutor(new ComfyClient(properties),
                new InputPreprocessor(), new WorkflowBinder(), properties);
    }

    private void stubObjectInfo() {
        comfy.stubFor(get(urlPathEqualTo("/object_info"))
                .willReturn(okJson(fixture("object-info.json"))));
    }

    private void stubUploadAndPromptOk() {
        comfy.stubFor(post(urlPathEqualTo("/upload/image"))
                .willReturn(okJson("{\"name\": \"photo.png\", \"subfolder\": \"kiano\", \"type\": \"input\"}")));
        comfy.stubFor(post(urlPathEqualTo("/prompt"))
                .willReturn(okJson(fixture("prompt-ok.json"))));
    }

    private ExecutionContext context() throws IOException {
        Path input = workDir.resolve("in/image.png");
        Files.createDirectories(input.getParent());
        Files.write(input, ImageCodec.png(new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB)));
        Path output = workDir.resolve("out/cutout.png");
        Files.createDirectories(output.getParent());
        return new ExecutionContext(workDir,
                Map.of("image", input), Map.of("cutout", output));
    }

    private JobPayload payload(String requiredNodeClasses) {
        String inputJson = """
                {"workflow": {"code": "CUTOUT", "version": 1,
                  "json": {
                    "1": {"class_type": "LoadImage", "inputs": {"image": "placeholder.png"}},
                    "6": {"class_type": "SaveImage", "inputs": {"filename_prefix": "cutout"}}},
                  "manifest": {
                    "code": "CUTOUT",
                    "inputs": {"image": {"node": "1", "field": "image", "maxLongSide": 2400}},
                    "outputs": {"cutout": {"node": "6"}},
                    "requiredNodeClasses": %s,
                    "models": []}},
                 "params": {},
                 "inputs": {"image": "t1/media/1.jpg"},
                 "outputs": {"cutout": "t1/gen/1/cutout.png"}}
                """.formatted(requiredNodeClasses);
        return new JobPayload(1, JobStep.CUTOUT, "main", ExecutorType.COMFYUI,
                mapper.readTree(inputJson));
    }

    @Test
    void videoOutput_readsConfiguredHistoryKey_gifs() throws Exception {
        stubObjectInfo();
        stubUploadAndPromptOk();
        comfy.stubFor(get(urlPathEqualTo(HISTORY)).willReturn(okJson(fixture("history-video.json"))));
        byte[] mp4 = mp4Bytes();
        comfy.stubFor(get(urlPathEqualTo("/view"))
                .withQueryParam("filename", equalTo("h3_hook_00001.mp4"))
                .withQueryParam("subfolder", equalTo("kiano"))
                .withQueryParam("type", equalTo("output"))
                .willReturn(aResponse().withBody(mp4)));

        JobResult result = executor().execute(videoPayload("gifs", 0), videoContext());

        assertThat(Files.readAllBytes(workDir.resolve("out/clip.mp4"))).isEqualTo(mp4);
        assertThat(result.gpuSeconds()).isCloseTo(0.18, within(1e-6));
        comfy.verify(1, getRequestedFor(urlPathEqualTo("/view")));
    }

    @Test
    void videoOutput_notMp4_nonRetryableOutputFormat() throws Exception {
        stubObjectInfo();
        stubUploadAndPromptOk();
        comfy.stubFor(get(urlPathEqualTo(HISTORY)).willReturn(okJson(fixture("history-video.json"))));
        comfy.stubFor(get(urlPathEqualTo("/view")).willReturn(aResponse()
                .withBody(ImageCodec.png(new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB)))));

        assertThatThrownBy(() -> executor().execute(videoPayload("gifs", 0), videoContext()))
                .isInstanceOfSatisfying(ExecutorException.class, ex -> {
                    assertThat(ex.retryable()).isFalse();
                    assertThat(ex.getMessage()).contains("OUTPUT_FORMAT");
                });
    }

    @Test
    void perJobTimeoutOverridesGlobal() throws Exception {
        properties.setTimeout(Duration.ofMinutes(15));
        stubUploadAndPromptOk();
        // /history stays unstubbed → WireMock's 404 = "not finished yet".

        assertThatThrownBy(() -> executor().execute(videoPayload("gifs", 1), videoContext()))
                .isInstanceOfSatisfying(ExecutorException.class, ex -> {
                    assertThat(ex.retryable()).isTrue();
                    assertThat(ex.getMessage()).contains("PT1S");
                });
        comfy.verify(1, postRequestedFor(urlPathEqualTo("/interrupt")));
    }

    @Test
    void existingSceneWorkflow_unchanged_defaultImagesKey() throws Exception {
        stubObjectInfo();
        stubUploadAndPromptOk();
        // the same history under the default key: outputs[node].images[0]
        comfy.stubFor(get(urlPathEqualTo(HISTORY))
                .willReturn(okJson(fixture("history-video.json").replace("\"gifs\"", "\"images\""))));
        byte[] mp4 = mp4Bytes();
        comfy.stubFor(get(urlPathEqualTo("/view"))
                .withQueryParam("filename", equalTo("h3_hook_00001.mp4"))
                .willReturn(aResponse().withBody(mp4)));

        executor().execute(videoPayload(null, 0), videoContext());

        assertThat(Files.readAllBytes(workDir.resolve("out/clip.mp4"))).isEqualTo(mp4);
    }

    private JobPayload videoPayload(@Nullable String historyField, int comfyTimeoutSeconds) {
        String binding = historyField == null
                ? "{\"node\": \"3\"}"
                : "{\"node\": \"3\", \"field\": \"" + historyField + "\"}";
        String inputJson = """
                {"workflow": {"code": "H3_I2V", "version": 1,
                  "json": {
                    "1": {"class_type": "LoadImage", "inputs": {"image": "placeholder.png"}},
                    "2": {"class_type": "MiniMaxH3I2V", "inputs": {"image": ["1", 0], "positive": "",
                        "seed": 0, "width": 720, "height": 1280, "frames": 120}},
                    "3": {"class_type": "VHS_VideoCombine", "inputs": {"images": ["2", 0],
                        "filename_prefix": "h3_hook"}}},
                  "manifest": {
                    "code": "H3_I2V",
                    "inputs": {"image": {"node": "1", "field": "image"}},
                    "params": {"positive": {"node": "2", "field": "positive"},
                      "seed": {"node": "2", "field": "seed"}, "width": {"node": "2", "field": "width"},
                      "height": {"node": "2", "field": "height"}, "frames": {"node": "2", "field": "frames"}},
                    "outputs": {"clip": %s},
                    "requiredNodeClasses": [], "models": []}},
                 "params": {"positive": "slow push in", "seed": 7, "width": 720, "height": 1280,
                    "frames": 120},
                 "inputs": {"image": "t1/media/1.jpg"},
                 "outputs": {"clip": "t1/gen/1/clip.mp4"},
                 "comfyTimeoutSeconds": %d}
                """.formatted(binding, comfyTimeoutSeconds);
        return new JobPayload(1, JobStep.H3_CLIP_LOCAL, "demo", ExecutorType.COMFYUI,
                mapper.readTree(inputJson));
    }

    private ExecutionContext videoContext() throws IOException {
        Path input = workDir.resolve("in/image.png");
        Files.createDirectories(input.getParent());
        Files.write(input, ImageCodec.png(new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB)));
        Path output = workDir.resolve("out/clip.mp4");
        Files.createDirectories(output.getParent());
        return new ExecutionContext(workDir, Map.of("image", input), Map.of("clip", output));
    }

    /** Minimal mp4: a size field followed by the ftyp box header. */
    private static byte[] mp4Bytes() {
        byte[] bytes = new byte[16];
        byte[] header = {'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        for (int i = 0; i < header.length; i++) {
            bytes[4 + i] = header[i];
        }
        return bytes;
    }

    private static String fixture(String name) {
        try (InputStream in = ComfyUIExecutorTest.class.getResourceAsStream("/comfy-replay/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
