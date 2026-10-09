package com.kiano.worker.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.workerprotocol.Edl;
import com.kiano.workerprotocol.Edl.Audio;
import com.kiano.workerprotocol.Edl.Kind;
import com.kiano.workerprotocol.Edl.Overlay;
import com.kiano.workerprotocol.Edl.Segment;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure command-shape assertions for the compose pipeline (C5 T7): cover crop
 * versus blurred fill, silenced segments, the overlay timeline and the music
 * rendition - all without running ffmpeg.
 */
class FfmpegCommandBuilderTest {

    /** Production-like paths: local input/output names carry no extension. */
    private static final Path VIDEO = Path.of("out", "video");
    private static final Path NO_MUSIC = Path.of("out", "video_nomusic");
    private static final Path POSTER = Path.of("out", "poster");

    private final FfmpegCommandBuilder builder = new FfmpegCommandBuilder();

    @Test
    void portraitSegment_coverCrop_landscape_blurFill() {
        String graph = filterOf(commands().get(0));

        // body (v1, portrait) fills the frame by cover-cropping
        assertThat(graph).contains("[1:v]trim=start=2.000:end=10.000,setpts=PTS-STARTPTS,"
                + "scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920,"
                + "fps=30,setsar=1,format=yuv420p[s1v]");
        // captions (v2, landscape) sit on a blurred cover of the same frame;
        // fps is converted before the split so the overlay keeps every frame
        assertThat(graph).contains(
                "[2:v]trim=start=1.000:end=6.000,setpts=PTS-STARTPTS,fps=30,setsar=1,split=2[s2bg][s2fg]");
        assertThat(graph).contains("[s2bg]scale=1080:1920:force_original_aspect_ratio=increase,"
                + "crop=1080:1920,boxblur=40:5[s2blur]");
        assertThat(graph).contains("[s2fg]scale=1080:1920:force_original_aspect_ratio=decrease[s2fit]");
        assertThat(graph).contains(
                "[s2blur][s2fit]overlay=(W-w)/2:(H-h)/2,setsar=1,format=yuv420p[s2v]");
        // only the landscape window is split; the others take the plain chain
        assertThat(graph.split("boxblur", -1)).hasSize(2);
    }

    @Test
    void silentSegments_useAnullsrcWithSegmentDuration() {
        List<String> master = commands().get(0);
        String graph = filterOf(master);

        // the H3 hook (3s) and the still end card (4s) are synthesized silence
        assertThat(graph).contains("anullsrc=r=48000:cl=stereo,atrim=duration=3.000,"
                + "asetpts=PTS-STARTPTS,aformat=sample_fmts=fltp:channel_layouts=stereo:"
                + "sample_rates=48000[s0a]");
        assertThat(graph).contains("anullsrc=r=48000:cl=stereo,atrim=duration=4.000,"
                + "asetpts=PTS-STARTPTS,aformat=sample_fmts=fltp:channel_layouts=stereo:"
                + "sample_rates=48000[s3a]");
        // real footage keeps its own audio at the configured volume
        assertThat(graph).contains("[1:a]atrim=start=2.000:end=10.000,asetpts=PTS-STARTPTS,"
                + "volume=0.500,aformat=sample_fmts=fltp:channel_layouts=stereo:"
                + "sample_rates=48000[s1a]");
        // the still image input is looped for its segment duration
        assertThat(master).containsSubsequence("-f", "image2", "-loop", "1", "-framerate", "30",
                "-t", "4.000", "-i", inputs().get("endcard").toString());
    }

    @Test
    void overlaysUseEnableBetweenTimeline() {
        List<String> master = commands().get(0);
        String graph = filterOf(master);

        // four segments, so the overlay inputs start at index 4
        assertThat(graph).contains("[catv][4:v]overlay=enable='between(t,0.000,3.000)'[o0]");
        assertThat(graph).contains("[o0][5:v]overlay=enable='between(t,3.000,11.000)'[o1]");
        assertThat(graph).contains("[o1][6:v]overlay=enable='between(t,11.000,13.500)'[o2]");
        assertThat(graph).contains("[o2][7:v]overlay=enable='between(t,13.500,16.000)'[o3]");
        assertThat(graph).contains("[o3]format=yuv420p[vout]");
        // overlay PNGs are looped for the whole timeline
        assertThat(master).containsSubsequence("-f", "image2", "-loop", "1", "-framerate", "30",
                "-t", "20.000", "-i", inputs().get("cap_hook").toString());
    }

    @Test
    void musicLoopedTrimmedAndFaded_noMusicHasOriginalOnly() {
        List<List<String>> commands = commands();
        assertThat(commands).hasSize(3);
        assertThat(commands).allSatisfy(command -> assertThat(command.get(0)).isEqualTo("ffmpeg"));

        List<String> music = commands.get(1);
        assertThat(music).containsSubsequence("-stream_loop", "-1", "-i",
                inputs().get("music").toString());
        String musicGraph = filterOf(music);
        assertThat(musicGraph).contains("[1:a]atrim=duration=20.000,asetpts=PTS-STARTPTS,"
                + "volume=0.350,afade=t=out:st=18.500:d=1.500[m]");
        assertThat(musicGraph).contains("[0:a][m]amix=inputs=2:normalize=0[am]");
        assertThat(musicGraph).contains("[am]loudnorm=I=-14:TP=-1.5:LRA=11[aout]");
        assertThat(music).containsSubsequence("-i", VIDEO.resolveSibling("master.mkv").toString(),
                "-stream_loop");
        assertThat(music).containsSubsequence("-c:v", "copy");
        assertThat(music).endsWith(VIDEO.toString());

        List<String> noMusic = commands.get(2);
        assertThat(noMusic).containsSubsequence("-i", VIDEO.resolveSibling("master.mkv").toString(),
                "-filter_complex");
        assertThat(filterOf(noMusic)).isEqualTo("[0:a]loudnorm=I=-14:TP=-1.5:LRA=11[aout]");
        assertThat(noMusic).containsSubsequence("-f", "mp4", NO_MUSIC.toString(),
                "-ss", "4.000", "-frames:v", "1", "-q:v", "2", "-f", "mjpeg", POSTER.toString());
        assertThat(noMusic).doesNotContain("amix", inputs().get("music").toString());
    }

    @Test
    void missingInput_failsFast() {
        Map<String, Path> incomplete = new LinkedHashMap<>(inputs());
        incomplete.remove("v2");

        assertThatThrownBy(() -> builder.build(edl(), incomplete, VIDEO, NO_MUSIC, POSTER,
                new FfmpegProperties()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("v2");
    }

    private List<List<String>> commands() {
        return builder.build(edl(), inputs(), VIDEO, NO_MUSIC, POSTER, new FfmpegProperties());
    }

    /** H3 hook (3s) + V1 body (8s) + V2 blur-filled captions (5s) + 4s end card. */
    private static Edl edl() {
        return new Edl("demo", 1080, 1920,
                List.of(
                        new Segment(Kind.H3, "hook", "h3_hook", 0, 3, false, false, null),
                        new Segment(Kind.REAL, "body", "v1", 2, 10, true, false, 11L),
                        new Segment(Kind.REAL, "captions", "v2", 1, 6, true, true, 12L),
                        new Segment(Kind.STILL, "endcard", "endcard", 0, 4, false, false, null)),
                List.of(new Overlay("cap_hook", 0, 3), new Overlay("cap_0", 3, 11),
                        new Overlay("cap_1", 11, 13.5), new Overlay("cap_2", 13.5, 16)),
                "music", new Audio(0.5, 0.35, 1.5));
    }

    private static Map<String, Path> inputs() {
        return Map.of(
                "h3_hook", Path.of("in", "h3_hook"),
                "v1", Path.of("in", "v1"),
                "v2", Path.of("in", "v2"),
                "endcard", Path.of("in", "endcard"),
                "cap_hook", Path.of("in", "cap_hook"),
                "cap_0", Path.of("in", "cap_0"),
                "cap_1", Path.of("in", "cap_1"),
                "cap_2", Path.of("in", "cap_2"),
                "music", Path.of("in", "music"));
    }

    private static String filterOf(List<String> command) {
        int index = command.indexOf("-filter_complex");
        assertThat(index).isGreaterThanOrEqualTo(0);
        return command.get(index + 1);
    }
}