package com.kiano.content.qc;

import com.kiano.content.media.MediaProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * VideoProbe backed by the ffprobe executable. Output is read on a
 * background thread so the 30s timeout cannot deadlock on a full pipe.
 */
@Component
public class FfprobeVideoProbe implements VideoProbe {

    private static final long TIMEOUT_SECONDS = 30;

    private final MediaProperties properties;

    public FfprobeVideoProbe(MediaProperties properties) {
        this.properties = properties;
    }

    @Override
    public VideoInfo probe(Path file) {
        ProcessBuilder builder = new ProcessBuilder(
                properties.getFfprobePath(),
                "-v", "error",
                "-print_format", "json",
                "-show_streams",
                "-show_format",
                file.toString());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> readStream(process.getInputStream(), output));
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new UnreadableMediaException("ffprobe timed out after " + TIMEOUT_SECONDS + "s");
            }
            reader.join(5000);
            if (process.exitValue() != 0) {
                throw new UnreadableMediaException(
                        "ffprobe exited with code " + process.exitValue() + ": " + output.toString().trim());
            }
            return FfprobeJsonParser.parse(output.toString());
        } catch (UnreadableMediaException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new UnreadableMediaException("Interrupted while probing video");
        } catch (IOException | RuntimeException ex) {
            throw new UnreadableMediaException("Cannot run ffprobe: " + ex.getMessage());
        }
    }

    private static void readStream(InputStream in, StringBuilder output) {
        try (in) {
            output.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // the probe result becomes garbage; the ffprobe exit code decides
        }
    }
}
