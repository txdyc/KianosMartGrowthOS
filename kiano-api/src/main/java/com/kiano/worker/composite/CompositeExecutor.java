package com.kiano.worker.composite;

import com.kiano.imaging.CutoutException;
import com.kiano.imaging.ImageCodec;
import com.kiano.imaging.SceneCanvasComposer;
import com.kiano.imaging.WhiteComposer;
import com.kiano.worker.ExecutionContext;
import com.kiano.worker.ExecutorException;
import com.kiano.worker.GenerationExecutor;
import com.kiano.worker.JobResult;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobPayload;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Deterministic compositor for the CPU-side pipeline steps: white pages
 * (main, angle, inbox) and the grey scene input canvas with its product
 * mask. Same input always yields the same pixels — no AI involved.
 */
@Component
@Profile("worker")
public class CompositeExecutor implements GenerationExecutor {

    @Override
    public ExecutorType type() {
        return ExecutorType.COMPOSITE;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public JobResult execute(JobPayload job, ExecutionContext ctx) throws ExecutorException {
        try {
            switch (job.step()) {
                case WHITE_MAIN, WHITE_ANGLE, INBOX -> composeWhite(job, ctx);
                case SCENE_INPUT -> composeScene(job, ctx);
                default -> throw new ExecutorException("UNSUPPORTED_STEP: " + job.step(), false);
            }
        } catch (CutoutException ex) {
            throw new ExecutorException(describe(ex), false);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new JobResult(0);
    }

    private void composeWhite(JobPayload job, ExecutionContext ctx) throws IOException {
        BufferedImage cutout = readCutout(ctx);
        JsonNode composer = job.input().path("composer");
        int canvas = composer.path("canvas").asInt(1600);
        double occupancy = composer.path("occupancy").asDouble(0.825);
        WhiteComposer.Composed composed = WhiteComposer.compose(cutout, canvas, occupancy);
        Files.write(ctx.outputs().get("image"), ImageCodec.png(composed.image()));
    }

    private void composeScene(JobPayload job, ExecutionContext ctx) throws IOException {
        BufferedImage cutout = readCutout(ctx);
        JsonNode placement = job.input().path("placement");
        int canvas = placement.path("canvas").asInt(1600);
        double occupancy = placement.path("occupancy").asDouble(0.60);
        double bottomMargin = placement.path("bottomMargin").asDouble(0.12);
        SceneCanvasComposer.SceneCanvas scene =
                SceneCanvasComposer.compose(cutout, canvas, occupancy, bottomMargin);
        Files.write(ctx.outputs().get("image"), ImageCodec.png(scene.image()));
        Files.write(ctx.outputs().get("mask"), ImageCodec.png(scene.productMask()));
    }

    private static BufferedImage readCutout(ExecutionContext ctx) throws IOException {
        return ImageCodec.read(Files.readAllBytes(ctx.inputs().get("cutout")));
    }

    /** "CUTOUT_EMPTY: ..." — the error code leads the message. */
    private static String describe(CutoutException ex) {
        return ex.getMessage().equals(ex.code()) ? ex.code() : ex.code() + ": " + ex.getMessage();
    }
}
