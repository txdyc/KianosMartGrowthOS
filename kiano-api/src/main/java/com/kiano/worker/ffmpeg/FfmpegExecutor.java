package com.kiano.worker.ffmpeg;

import com.kiano.worker.ExecutionContext;
import com.kiano.worker.ExecutorException;
import com.kiano.worker.ExecutorUnavailableException;
import com.kiano.worker.GenerationExecutor;
import com.kiano.worker.JobResult;
import com.kiano.workerprotocol.Edl;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobPayload;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * FFMPEG executor (C5 T7): reads the job's EDL and runs the three compose
 * commands in order - master, music rendition, no-music rendition plus poster.
 * A non-zero exit is a non-retryable failure carrying the stderr tail (the
 * input was invalid); an ffmpeg that cannot start is an unavailable executor
 * and a hung command is killed and fails. Compose is CPU work, so it reports
 * no GPU seconds.
 */
@Component
@Profile("worker")
public class FfmpegExecutor implements GenerationExecutor {

    private static final Duration AVAILABILITY_CACHE = Duration.ofSeconds(10);
    private static final int STDERR_TAIL_CHARS = 2048;
    private static final long PROBE_TIMEOUT_SECONDS = 5;
    private static final long READER_JOIN_MILLIS = 1000;

    private final FfmpegProperties properties;
    private final FfmpegCommandBuilder builder = new FfmpegCommandBuilder();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private volatile Boolean available;
    private volatile long availableAt;

    public FfmpegExecutor(FfmpegProperties properties) {
        this.properties = properties;
    }

    @Override
    public ExecutorType type() {
        return ExecutorType.FFMPEG;
    }

    @Override
    public boolean available() {
        Boolean cached = available;
        if (cached != null && System.nanoTime() - availableAt < AVAILABILITY_CACHE.toNanos()) {
            return cached;
        }
        boolean up = probe();
        available = up;
        availableAt = System.nanoTime();
        return up;
    }

    @Override
    public JobResult execute(JobPayload job, ExecutionContext ctx) throws ExecutorException {
        Edl edl = readEdl(job);
        List<List<String>> commands;
        try {
            commands = builder.build(edl, ctx.inputs(), output(ctx, "video"),
                    output(ctx, "video_nomusic"), output(ctx, "poster"), properties);
        } catch (IllegalArgumentException ex) {
            throw new ExecutorException(ex.getMessage(), false);
        }
        for (int step = 0; step < commands.size(); step++) {
            run(commands.get(step), step + 1);
        }
        return new JobResult(0);
    }

    private Edl readEdl(JobPayload job) throws ExecutorException {
        if (job.input().path("edl").isMissingNode()) {
            throw new ExecutorException("INVALID_EDL: the job input carries no edl", false);
        }
        try {
            return mapper.treeToValue(job.input().path("edl"), Edl.class);
        } catch (RuntimeException ex) {
            throw new ExecutorException("INVALID_EDL: " + ex.getMessage(), false);
        }
    }

    private static Path output(ExecutionContext ctx, String name) throws ExecutorException {
        Path path = ctx.outputs().get(name);
        if (path == null) {
            throw new ExecutorException("OUTPUT_MISSING: the job promises no " + name, false);
        }
        return path;
    }

    /** Runs one command; stderr is drained on a thread so the pipes stay open. */
    private void run(List<String> command, int step) throws ExecutorException {
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException ex) {
            throw new ExecutorUnavailableException(
                    "Cannot start ffmpeg (" + properties.getFfmpegPath() + "): " + ex.getMessage());
        }
        StringBuilder stderr = new StringBuilder();
        Thread reader = new Thread(() -> readStderr(process.getErrorStream(), stderr),
                "kiano-ffmpeg-stderr-" + step);
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(properties.getFfmpegTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                reader.join(READER_JOIN_MILLIS);
                throw new ExecutorException("ffmpeg step " + step + " timed out after "
                        + properties.getFfmpegTimeout() + ": " + tail(stderr), false);
            }
            reader.join(READER_JOIN_MILLIS);
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ExecutorException("Interrupted while running ffmpeg step " + step, true);
        }
        if (process.exitValue() != 0) {
            throw new ExecutorException("ffmpeg exited with code " + process.exitValue()
                    + " (step " + step + "): " + tail(stderr), false);
        }
    }

    private boolean probe() {
        try {
            Process process = new ProcessBuilder(properties.getFfmpegPath(), "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    && process.exitValue() == 0;
        } catch (IOException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void readStderr(InputStream in, StringBuilder output) {
        try (in) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // the exit code decides; a truncated tail still names the failure
        }
    }

    /** Only the last 2 KB of stderr ever reach the job error. */
    private static String tail(StringBuilder output) {
        String text = output.toString().strip();
        return text.length() <= STDERR_TAIL_CHARS ? text
                : text.substring(text.length() - STDERR_TAIL_CHARS);
    }
}