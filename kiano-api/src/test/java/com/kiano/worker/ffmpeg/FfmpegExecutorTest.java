package com.kiano.worker.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.kiano.imaging.ImageCodec;
import com.kiano.worker.ExecutionContext;
import com.kiano.worker.ExecutorException;
import com.kiano.worker.JobResult;
import com.kiano.workerprotocol.Edl;
import com.kiano.workerprotocol.Edl.Audio;
import com.kiano.workerprotocol.Edl.Kind;
import com.kiano.workerprotocol.Edl.Overlay;
import com.kiano.workerprotocol.Edl.Segment;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobPayload;
import com.kiano.workerprotocol.JobStep;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * FfmpegExecutor against a real ffmpeg (Review Focus 1): the sources are a
 * portrait 30fps clip with audio, a 1920x1080 clip carrying rotate=90
 * metadata, a variable-frame-rate clip without an audio track and a still end
 * card. The composed renditions must be 1080x1920 h264/aac at 30fps with the
 * EDL duration (plus or minus 0.1s).
 */
@EnabledIf("ffmpegAvailable")
class FfmpegExecutorTest {

    /** EDL total: 3s hook + 8s body + 5s captions + 4s end card. */
    private static final double TOTAL_SECONDS = 20;

    @TempDir
    static Path workDir;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final FfmpegProperties properties = new FfmpegProperties();
    private final FfmpegExecutor executor = new FfmpegExecutor(properties);

    /** Input files keep their bare key as the name, like a real job download. */
    private static Path input(String key) {
        return workDir.resolve("in").resolve(key);
    }

    static boolean ffmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").start();
            return process.waitFor() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    @BeforeAll
    static void generateSources() throws Exception {
        Files.createDirectories(workDir.resolve("in"));
        Files.createDirectories(workDir.resolve("gen"));
        // 12s 1080x1920@30 with a tone: the demo hook/body footage
        Path portrait = workDir.resolve("gen/v1.mp4");
        run(List.of("ffmpeg", "-y", "-v", "error", "-f", "lavfi",
                "-i", "testsrc=size=1080x1920:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "12", "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", portrait.toString()));
        copyInto(portrait, "v1");
        // 8s 1920x1080 with rotate=90 side data: the landscape captions source
        Path landscape = workDir.resolve("gen/v2.mp4");
        run(List.of("ffmpeg", "-y", "-v", "error", "-f", "lavfi",
                "-i", "testsrc=size=1920x1080:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=330:sample_rate=48000",
                "-t", "8", "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", "-metadata:s:v:0", "rotate=90",
                landscape.toString()));
        copyInto(landscape, "v2");
        // 8s variable-frame-rate 1080x1920 (from 60fps) without any audio track
        Path vfr = workDir.resolve("gen/vfr.mp4");
        run(List.of("ffmpeg", "-y", "-v", "error", "-f", "lavfi",
                "-i", "testsrc=size=1080x1920:rate=60",
                "-vf", "select='lt(mod(n\\,120)\\,80)'", "-fps_mode", "vfr", "-t", "8",
                "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                vfr.toString()));
        copyInto(vfr, "vfr");
        // 3s tone: shorter than the video, so the mix has to loop it
        Path music = workDir.resolve("gen/music.wav");
        run(List.of("ffmpeg", "-y", "-v", "error", "-f", "lavfi",
                "-i", "sine=frequency=220:sample_rate=48000", "-t", "3", music.toString()));
        copyInto(music, "music");
        // end card (opaque) and semi-transparent caption cards
        writePng("endcard", endCard());
        writePng("cap_hook", captionCard(new Color(0, 0, 0, 140)));
        writePng("cap_0", captionCard(new Color(0, 0, 0, 120)));
        writePng("cap_1", captionCard(new Color(0, 0, 0, 100)));
        writePng("cap_2", captionCard(new Color(0, 0, 0, 80)));
    }

    @Test
    void composesAllVariants_to1080x1920_30fps_h264_aac_durationMatchesEdl() throws Exception {
        ExecutionContext ctx = context();
        JobResult result = executor.execute(payload(edl()), ctx);

        assertThat(executor.type()).isEqualTo(ExecutorType.FFMPEG);
        assertThat(executor.available()).isTrue();
        assertThat(result.gpuSeconds()).isZero();

        JsonNode video = probe(ctx.outputs().get("video"));
        JsonNode videoStream = stream(video, "video");
        assertThat(videoStream.path("codec_name").asString()).isEqualTo("h264");
        assertThat(videoStream.path("profile").asString()).isEqualTo("High");
        assertThat(videoStream.path("width").asInt()).isEqualTo(1080);
        assertThat(videoStream.path("height").asInt()).isEqualTo(1920);
        assertThat(videoStream.path("avg_frame_rate").asString()).isEqualTo("30/1");
        assertThat(videoStream.path("pix_fmt").asString()).isEqualTo("yuv420p");
        JsonNode audioStream = stream(video, "audio");
        assertThat(audioStream.path("codec_name").asString()).isEqualTo("aac");
        assertThat(audioStream.path("sample_rate").asString()).isEqualTo("48000");
        assertThat(audioStream.path("channels").asInt()).isEqualTo(2);
        assertThat(duration(video)).isCloseTo(TOTAL_SECONDS, within(0.1));

        // the no-music rendition carries the same picture and duration
        JsonNode noMusic = probe(ctx.outputs().get("video_nomusic"));
        assertThat(stream(noMusic, "video").path("codec_name").asString()).isEqualTo("h264");
        assertThat(stream(noMusic, "audio").path("codec_name").asString()).isEqualTo("aac");
        assertThat(duration(noMusic)).isCloseTo(TOTAL_SECONDS, within(0.1));
    }

    @Test
    void noAudioSource_producesSilenceNotError() throws Exception {
        // every segment comes from the track-less source: all audio is anullsrc
        Edl silent = new Edl("demo", 1080, 1920,
                List.of(
                        new Segment(Kind.REAL, "hook", "vfr", 0, 3, false, false, 31L),
                        new Segment(Kind.REAL, "body", "vfr", 0, 4, false, false, 31L),
                        new Segment(Kind.REAL, "captions", "vfr", 0, 4, false, false, 31L),
                        new Segment(Kind.STILL, "endcard", "endcard", 0, 4, false, false, null)),
                List.of(), null, new Audio(0.5, 0.35, 1.5));
        ExecutionContext ctx = context();

        executor.execute(payload(silent), ctx);

        JsonNode video = probe(ctx.outputs().get("video"));
        assertThat(stream(video, "audio").path("codec_name").asString()).isEqualTo("aac");
        assertThat(duration(video)).isCloseTo(15, within(0.1));
        assertThat(meanVolumeDb(ctx.outputs().get("video"))).isLessThan(-70);
    }

    @Test
    void writesNoMusicRenditionAndPoster() throws Exception {
        ExecutionContext ctx = context();

        executor.execute(payload(edl()), ctx);

        Path video = ctx.outputs().get("video");
        Path noMusic = ctx.outputs().get("video_nomusic");
        Path poster = ctx.outputs().get("poster");
        assertThat(Files.size(video)).isPositive();
        assertThat(Files.size(noMusic)).isPositive();
        // the poster is a JPEG still of the composed frame
        byte[] posterBytes = Files.readAllBytes(poster);
        assertThat(posterBytes[0] & 0xFF).isEqualTo(0xFF);
        assertThat(posterBytes[1] & 0xFF).isEqualTo(0xD8);
        BufferedImage posterImage = ImageCodec.read(posterBytes);
        assertThat(posterImage.getWidth()).isEqualTo(1080);
        assertThat(posterImage.getHeight()).isEqualTo(1920);
    }

    @Test
    void corruptInput_nonRetryableWithStderrTail() throws Exception {
        Path corruptIn = workDir.resolve("corrupt/in");
        Files.createDirectories(corruptIn);
        Path corrupt = corruptIn.resolve("v1");
        Files.write(corrupt, "this is not a video file".getBytes(StandardCharsets.UTF_8));
        Edl oneSegment = new Edl("demo", 1080, 1920,
                List.of(
                        new Segment(Kind.REAL, "hook", "v1", 0, 3, false, false, 11L),
                        new Segment(Kind.STILL, "endcard", "endcard", 0, 4, false, false, null)),
                List.of(), null, new Audio(0.5, 0.35, 1.5));
        Map<String, Path> inputs = new LinkedHashMap<>();
        inputs.put("v1", corrupt);
        inputs.put("endcard", input("endcard"));

        ExecutionContext ctx = new ExecutionContext(workDir, inputs, outputs());

        assertThatThrownBy(() -> executor.execute(payload(oneSegment), ctx))
                .isInstanceOfSatisfying(ExecutorException.class, ex -> {
                    assertThat(ex.retryable()).isFalse();
                    assertThat(ex.getMessage())
                            .contains("ffmpeg exited with code")
                            .contains("Invalid data found when processing input");
                });
    }

    /** H3-free demo cut: a VFR hook, V1 body, blur-filled V2 captions, end card. */
    private static Edl edl() {
        return new Edl("demo", 1080, 1920,
                List.of(
                        new Segment(Kind.REAL, "hook", "vfr", 0, 3, false, false, 31L),
                        new Segment(Kind.REAL, "body", "v1", 0, 8, true, false, 11L),
                        new Segment(Kind.REAL, "captions", "v2", 0, 5, true, true, 12L),
                        new Segment(Kind.STILL, "endcard", "endcard", 0, 4, false, false, null)),
                List.of(new Overlay("cap_hook", 0, 3), new Overlay("cap_0", 3, 11),
                        new Overlay("cap_1", 11, 13.5), new Overlay("cap_2", 13.5, 16)),
                "music", new Audio(0.5, 0.35, 1.5));
    }

    /** Mirrors the worker loop: inputs and extension-less outputs in the work dir. */
    private ExecutionContext context() throws Exception {
        Map<String, Path> inputs = new LinkedHashMap<>();
        for (String key : List.of("vfr", "v1", "v2", "endcard", "cap_hook", "cap_0", "cap_1",
                "cap_2", "music")) {
            inputs.put(key, input(key));
        }
        return new ExecutionContext(workDir, inputs, outputs());
    }

    private Map<String, Path> outputs() throws Exception {
        Path out = workDir.resolve("out");
        Files.createDirectories(out);
        Map<String, Path> outputs = new LinkedHashMap<>();
        outputs.put("video", out.resolve("video"));
        outputs.put("video_nomusic", out.resolve("video_nomusic"));
        outputs.put("poster", out.resolve("poster"));
        return outputs;
    }

    private JobPayload payload(Edl edl) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("edl", edl);
        input.put("inputs", Map.of("vfr", "t1/media/vfr.mp4", "v1", "t1/media/v1.mp4",
                "v2", "t1/media/v2.mp4", "endcard", "t1/video-inputs/9/demo/endcard.png",
                "music", "t1/music/1/track.wav"));
        input.put("outputs", Map.of("video", "t1/gen/9/video.mp4",
                "video_nomusic", "t1/gen/9/video_nomusic.mp4", "poster", "t1/gen/9/poster.jpg"));
        return new JobPayload(9, JobStep.VIDEO_COMPOSE, "demo", ExecutorType.FFMPEG,
                mapper.valueToTree(input));
    }

    private JsonNode probe(Path file) throws Exception {
        Process process = new ProcessBuilder("ffprobe", "-v", "error", "-print_format", "json",
                "-show_streams", "-show_format", file.toString()).redirectErrorStream(true).start();
        String json = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("ffprobe on %s: %s", file, json).isZero();
        return mapper.readTree(json);
    }

    private static JsonNode stream(JsonNode probe, String codecType) {
        for (JsonNode stream : probe.path("streams")) {
            if (codecType.equals(stream.path("codec_type").asString())) {
                return stream;
            }
        }
        throw new AssertionError("No " + codecType + " stream in " + probe);
    }

    private static double duration(JsonNode probe) {
        return Double.parseDouble(probe.path("format").path("duration").asString());
    }

    /** Mean volume of the whole file; -inf (digital silence) parses to a very low value. */
    private static double meanVolumeDb(Path file) throws Exception {
        Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-i", file.toString(),
                "-af", "volumedetect", "-f", "null", "-").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isZero();
        Matcher matcher = Pattern.compile("mean_volume: (-?[\\d.]+) dB").matcher(output);
        assertThat(matcher.find()).as("volumedetect output: %s", output).isTrue();
        return Double.parseDouble(matcher.group(1));
    }

    private static BufferedImage endCard() {
        BufferedImage image = new BufferedImage(1080, 1920, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0xD9, 0x76, 0x1A));
        g.fillRect(0, 0, 1080, 1920);
        g.setColor(Color.WHITE);
        g.fillRect(120, 840, 840, 240);
        g.dispose();
        return image;
    }

    private static BufferedImage captionCard(Color fill) {
        BufferedImage image = new BufferedImage(1080, 1920, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(fill);
        g.fillRoundRect(80, 980, 920, 280, 48, 48);
        g.dispose();
        return image;
    }

    private static void writePng(String key, BufferedImage image) throws Exception {
        Files.write(input(key), ImageCodec.png(image));
    }

    private static void copyInto(Path source, String key) throws Exception {
        Files.copy(source, input(key), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("ffmpeg failed: " + output);
        }
    }
}