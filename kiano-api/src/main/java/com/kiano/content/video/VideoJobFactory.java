package com.kiano.content.video;

import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.workflow.WorkflowRegistry;
import com.kiano.content.workflow.WorkflowRegistry.ComfyWorkflowView;
import com.kiano.content.video.h3.H3Properties;
import com.kiano.workerprotocol.Edl;
import com.kiano.workerprotocol.JobStep;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Creates the video jobs of one type (C5 Task 9) from a {@link VideoTypeContext}:
 * the local H3 clip on the active H3_I2V workflow, the H3 API clip (which the
 * api's H3JobRunner executes) and the final VIDEO_COMPOSE. Both H3 backends use
 * the same context, so escalation reuses the same prompt, first frame, window
 * plan and compose seeds. Compose dedupes on {@code composeKey}: one type
 * context produces exactly one VIDEO_COMPOSE job (Review Focus 3).
 */
@Component
public class VideoJobFactory {

    private final GenerationJobStore jobs;
    private final WorkflowRegistry workflows;
    private final H3Properties h3Properties;
    private final Random random = new Random();

    public VideoJobFactory(GenerationJobStore jobs, WorkflowRegistry workflows,
            H3Properties h3Properties) {
        this.jobs = jobs;
        this.workflows = workflows;
        this.h3Properties = h3Properties;
    }

    /** True when this type context already has a VIDEO_COMPOSE job in the run. */
    public boolean composeExists(long tenantId, long runId, String composeKey) {
        return jobs.findByRun(tenantId, runId).stream()
                .anyMatch(job -> job.step() == JobStep.VIDEO_COMPOSE
                        && composeKey.equals(job.input().path("composeKey").asString("")));
    }

    /** Empty when no H3_I2V workflow is active (the caller falls back to the API). */
    public Optional<Long> createH3Local(long tenantId, long productId, long runId,
            VideoTypeContext context) {
        Optional<ComfyWorkflowView> active = workflows.active(tenantId, "H3_I2V");
        if (active.isEmpty()) {
            return Optional.empty();
        }
        ComfyWorkflowView workflow = active.get();
        int fps = h3Properties.getLocal().getFps();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("positive", context.prompt());
        params.put("negative", "");
        params.put("seed", random.nextLong() & Long.MAX_VALUE);
        params.put("width", h3Properties.getLocal().getWidth());
        params.put("height", h3Properties.getLocal().getHeight());
        params.put("frames", h3Properties.getRequestSeconds() * fps);
        params.put("fps", fps);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("workflow", Map.of("code", workflow.code(), "version", workflow.version(),
                "json", workflow.workflow(), "manifest", workflow.manifest()));
        input.put("params", params);
        input.put("inputs", Map.of("image", context.firstFrameKey()));
        input.put("outputs", Map.of("clip", context.h3ClipKey()));
        input.put("comfyTimeoutSeconds", h3Properties.getLocal().getComfyTimeoutSeconds());
        input.put("fallbackWindow", window(context.fallbackWindow()));
        input.put("typeContext", context);
        return Optional.of(jobs.create(tenantId, productId, runId, JobStep.H3_CLIP_LOCAL,
                context.type(), input, List.of()));
    }

    /** H3 API clip; the api's H3JobRunner submits and polls it. */
    public long createH3Api(long tenantId, long productId, long runId, VideoTypeContext context) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("prompt", context.prompt());
        input.put("firstFrameKey", context.firstFrameKey());
        input.put("outputs", Map.of("clip", context.h3ClipKey()));
        input.put("fallbackWindow", window(context.fallbackWindow()));
        input.put("typeContext", context);
        return jobs.create(tenantId, productId, runId, JobStep.H3_CLIP_API, context.type(),
                input, List.of());
    }

    /** The final FFMPEG compose job: EDL, object keys and the composed provenance. */
    public long createCompose(long tenantId, long productId, long runId, VideoTypeContext context,
            Edl edl, Map<String, String> inputs, Map<String, Object> provenance) {
        return createCompose(tenantId, productId, runId, context.type(), context.composeKey(), edl,
                inputs, context.outputs(), provenance, null);
    }

    /**
     * A compose job from raw parts - used by the price re-render, which starts
     * from the old cut instead of a type context and marks the STALE version it
     * replaces so the new one can auto-approve.
     */
    public long createCompose(long tenantId, long productId, long runId, String type,
            String composeKey, Edl edl, Map<String, String> inputs, Map<String, String> outputs,
            Map<String, Object> provenance, @Nullable Long autoApproveFrom) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("composeKey", composeKey);
        input.put("edl", edl);
        input.put("inputs", referencedInputs(edl, inputs));
        input.put("outputs", outputs);
        input.put("provenance", provenance);
        if (autoApproveFrom != null) {
            input.put("autoApproveFrom", autoApproveFrom);
        }
        return jobs.create(tenantId, productId, runId, JobStep.VIDEO_COMPOSE, type, input,
                List.of());
    }

    /** The compose job already created for this context, if any. */
    public Optional<GenerationJob> composeJob(long tenantId, long runId, String composeKey) {
        return jobs.findByRun(tenantId, runId).stream()
                .filter(job -> job.step() == JobStep.VIDEO_COMPOSE
                        && composeKey.equals(job.input().path("composeKey").asString("")))
                .findFirst();
    }

    /**
     * Only the object keys the EDL references are handed to the worker; every
     * referenced input must have one (a trimmed H3 cut without its clip fails here).
     */
    private static Map<String, String> referencedInputs(Edl edl, Map<String, String> inputs) {
        Set<String> keys = new LinkedHashSet<>();
        edl.segments().forEach(segment -> keys.add(segment.input()));
        edl.overlays().forEach(overlay -> keys.add(overlay.input()));
        if (edl.musicInput() != null) {
            keys.add(edl.musicInput());
        }
        Map<String, String> referenced = new LinkedHashMap<>();
        for (String key : keys) {
            String objectKey = inputs.get(key);
            if (objectKey == null || objectKey.isBlank()) {
                // fail here, not on the worker minutes later with INPUT_MISSING
                throw new IllegalStateException("The EDL uses input '" + key
                        + "' but no object key was provided for it");
            }
            referenced.put(key, objectKey);
        }
        return referenced;
    }

    private static Map<String, Object> window(VideoPlanner.Window window) {
        return Map.of("in", window.in(), "out", window.out());
    }
}