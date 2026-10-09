package com.kiano.worker;

import com.kiano.workerprotocol.FfprobeJsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Default {@link OutputProbe} backed by the ffprobe executable configured at
 * {@code kiano.worker.ffprobe-path}. Output is read on a background thread so
 * the timeout cannot deadlock on a full pipe.
 */
@Component
@Profile("worker")
public class FfprobeOutputProbe implements OutputProbe {

    private static final long TIMEOUT_SECONDS = 30;

    private final WorkerProperties properties;

    public FfprobeOutputProbe(WorkerProperties properties) {
        this.properties = properties;
    }

    @Override
    public VideoOutput probe(Path file) {
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
                throw new IllegalStateException("ffprobe timed out after " + TIMEOUT_SECONDS + "s");
            }
            reader.join(5000);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("ffprobe exited with code " + process.exitValue());
            }
            var info = FfprobeJsonParser.parse(output.toString());
            return new VideoOutput(info.durationSeconds(), info.width(), info.height());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while probing output", ex);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("Cannot probe output with ffprobe: " + ex.getMessage(), ex);
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
