package com.kiano.worker.ffmpeg;

import com.kiano.content.video.EdlMath;
import com.kiano.workerprotocol.Edl;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the three ffmpeg commands of a VIDEO_COMPOSE job (C5 T7):
 * <ol>
 *   <li>the master: every segment trimmed (stills looped for their segment
 *       duration), normalized to 1080x1920x30 - a cover crop for portrait
 *       footage, a blurred fill under the contained frame otherwise - then
 *       concatenated and overlaid with the caption PNGs on the output
 *       timeline. The master already carries the final H.264 settings so the
 *       renditions only re-encode audio;</li>
 *   <li>the music rendition: the master audio mixed with the looped, trimmed
 *       and faded track under loudnorm, video stream copied;</li>
 *   <li>the no-music rendition and the poster frame, both cut from the
 *       master.</li>
 * </ol>
 * Paths come from the job: the worker downloads inputs and creates outputs
 * under their bare keys (no extension), so image inputs pin the image2
 * demuxer and every output pins its muxer.
 */
public final class FfmpegCommandBuilder {

    private static final String COVER =
            "scale=1080:1920:force_original_aspect_ratio=increase,crop=1080:1920";
    private static final String FRAME_FORMAT = "fps=30,setsar=1,format=yuv420p";
    private static final String SILENCE = "anullsrc=r=48000:cl=stereo";
    private static final String AUDIO_FORMAT =
            "aformat=sample_fmts=fltp:channel_layouts=stereo:sample_rates=48000";
    private static final String LOUDNORM = "loudnorm=I=-14:TP=-1.5:LRA=11";

    public List<List<String>> build(Edl edl, Map<String, Path> inputs, Path video, Path noMusic,
            Path poster, FfmpegProperties properties) {
        Path master = video.resolveSibling("master.mkv");
        return List.of(
                masterCommand(edl, inputs, master, properties),
                musicCommand(edl, inputs, master, video, properties),
                renditionsCommand(edl, master, noMusic, poster, properties));
    }

    /** Picture plus original sound, normalized to the final video format. */
    private static List<String> masterCommand(Edl edl, Map<String, Path> inputs, Path master,
            FfmpegProperties properties) {
        List<String> command = new ArrayList<>();
        command.add(properties.getFfmpegPath());
        command.addAll(List.of("-y", "-v", "error"));
        double total = EdlMath.totalSeconds(edl);
        for (Edl.Segment segment : edl.segments()) {
            Path file = requireInput(inputs, segment.input());
            if (segment.kind() == Edl.Kind.STILL) {
                command.addAll(List.of("-f", "image2", "-loop", "1", "-framerate", "30",
                        "-t", number(segment.out() - segment.in()), "-i", file.toString()));
            } else {
                command.addAll(List.of("-i", file.toString()));
            }
        }
        for (Edl.Overlay overlay : edl.overlays()) {
            command.addAll(List.of("-f", "image2", "-loop", "1", "-framerate", "30",
                    "-t", number(total), "-i", requireInput(inputs, overlay.input()).toString()));
        }
        command.addAll(List.of("-filter_complex", String.join(";", masterGraph(edl)),
                "-map", "[vout]", "-map", "[cata]",
                "-c:v", "libx264", "-profile:v", "high", "-crf", "20", "-preset", "medium",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2",
                master.toString()));
        return command;
    }

    private static List<String> masterGraph(Edl edl) {
        List<Edl.Segment> segments = edl.segments();
        List<String> graph = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            graph.addAll(videoChain(i, segments.get(i)));
            graph.add(audioChain(i, segments.get(i), edl.audio().originalVolume()));
        }
        StringBuilder concat = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            concat.append("[s").append(i).append("v][s").append(i).append("a]");
        }
        concat.append("concat=n=").append(segments.size()).append(":v=1:a=1[catv][cata]");
        graph.add(concat.toString());
        String previous = "catv";
        for (int i = 0; i < edl.overlays().size(); i++) {
            Edl.Overlay overlay = edl.overlays().get(i);
            graph.add("[" + previous + "][" + (segments.size() + i)
                    + ":v]overlay=enable='between(t," + number(overlay.start()) + ","
                    + number(overlay.end()) + ")'[o" + i + "]");
            previous = "o" + i;
        }
        graph.add("[" + previous + "]format=yuv420p[vout]");
        return graph;
    }

    /**
     * One segment normalized to the frame; stills are already bounded by -t.
     * The cover crop is applied before the fps conversion; the blur fill
     * converts first so the overlay's frame sync cannot drop the last frame.
     */
    private static List<String> videoChain(int index, Edl.Segment segment) {
        String out = "[s" + index + "v]";
        if (segment.kind() == Edl.Kind.STILL) {
            return List.of("[" + index + ":v]setpts=PTS-STARTPTS," + COVER + ","
                    + FRAME_FORMAT + out);
        }
        String head = "[" + index + ":v]trim=start=" + number(segment.in()) + ":end="
                + number(segment.out()) + ",setpts=PTS-STARTPTS,";
        if (!segment.blurFill()) {
            return List.of(head + COVER + "," + FRAME_FORMAT + out);
        }
        return List.of(
                head + "fps=30,setsar=1,split=2[s" + index + "bg][s" + index + "fg]",
                "[s" + index + "bg]" + COVER + ",boxblur=40:5[s" + index + "blur]",
                "[s" + index + "fg]scale=1080:1920:force_original_aspect_ratio=decrease[s"
                        + index + "fit]",
                "[s" + index + "blur][s" + index + "fit]overlay=(W-w)/2:(H-h)/2,"
                        + "setsar=1,format=yuv420p" + out);
    }

    /**
     * Real footage keeps its own audio at {@code originalVolume}; H3 clips,
     * stills and track-less sources are replaced by silence of the segment
     * duration so the concat stays aligned.
     */
    private static String audioChain(int index, Edl.Segment segment, double originalVolume) {
        String out = "[s" + index + "a]";
        if (segment.hasAudio()) {
            return "[" + index + ":a]atrim=start=" + number(segment.in()) + ":end="
                    + number(segment.out()) + ",asetpts=PTS-STARTPTS,volume="
                    + number(originalVolume) + "," + AUDIO_FORMAT + out;
        }
        return SILENCE + ",atrim=duration=" + number(segment.out() - segment.in())
                + ",asetpts=PTS-STARTPTS," + AUDIO_FORMAT + out;
    }

    /** Master audio plus the looped, trimmed and faded track under loudnorm. */
    private static List<String> musicCommand(Edl edl, Map<String, Path> inputs, Path master, Path video,
            FfmpegProperties properties) {
        List<String> command = new ArrayList<>();
        command.add(properties.getFfmpegPath());
        command.addAll(List.of("-y", "-v", "error", "-i", master.toString()));
        String graph;
        if (edl.musicInput() == null) {
            // no track was picked: the rendition carries the original sound only
            graph = "[0:a]" + LOUDNORM + "[aout]";
        } else {
            command.addAll(List.of("-stream_loop", "-1", "-i",
                    requireInput(inputs, edl.musicInput()).toString()));
            double total = EdlMath.totalSeconds(edl);
            double fadeOut = edl.audio().musicFadeOut();
            graph = "[1:a]atrim=duration=" + number(total) + ",asetpts=PTS-STARTPTS,volume="
                    + number(edl.audio().musicVolume()) + ",afade=t=out:st="
                    + number(total - fadeOut) + ":d=" + number(fadeOut) + "[m]"
                    + ";[0:a][m]amix=inputs=2:normalize=0[am]"
                    + ";[am]" + LOUDNORM + "[aout]";
        }
        command.addAll(List.of("-filter_complex", graph,
                "-map", "0:v", "-map", "[aout]",
                "-c:v", "copy",
                "-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-ac", "2",
                "-movflags", "+faststart", "-shortest",
                "-f", "mp4", video.toString()));
        return command;
    }

    /**
     * The no-music rendition (original sound only) and a poster frame at
     * {@code min(4, total/2)} seconds, both cut from the master in one pass.
     */
    private static List<String> renditionsCommand(Edl edl, Path master, Path noMusic, Path poster,
            FfmpegProperties properties) {
        double total = EdlMath.totalSeconds(edl);
        List<String> command = new ArrayList<>();
        command.add(properties.getFfmpegPath());
        command.addAll(List.of("-y", "-v", "error", "-i", master.toString(),
                "-filter_complex", "[0:a]" + LOUDNORM + "[aout]",
                "-map", "0:v", "-map", "[aout]",
                "-c:v", "copy",
                "-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-ac", "2",
                "-movflags", "+faststart", "-shortest",
                "-f", "mp4", noMusic.toString(),
                "-ss", number(Math.min(4.0, total / 2)), "-frames:v", "1", "-q:v", "2",
                "-f", "mjpeg", poster.toString()));
        return command;
    }

    private static Path requireInput(Map<String, Path> inputs, String key) {
        Path path = inputs.get(key);
        if (path == null) {
            throw new IllegalArgumentException("INPUT_MISSING: the EDL uses input " + key
                    + " but the job does not provide it");
        }
        return path;
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}