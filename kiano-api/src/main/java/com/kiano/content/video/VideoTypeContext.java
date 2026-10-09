package com.kiano.content.video;

import com.kiano.content.video.h3.H3Backend;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * The per-type seed carried in every H3 job input ({@code typeContext}) and
 * reused by the coordinator to create the VIDEO_COMPOSE job (C5 Task 9): the
 * chosen windows with the plan input they came from, the compose input/output
 * object keys, the seed provenance, the reviewed first frame plus the shared
 * I2VA prompt, and the escalation attempts so far. It travels as JSON inside
 * the job, so an api restart can keep escalating from the job alone.
 */
public record VideoTypeContext(
        String composeKey,
        String type,
        String sku,
        int candidate,
        @Nullable H3Backend backend,
        String prompt,
        String firstFrameKey,
        String sceneLabel,
        VideoPlanner.PlanInput planInput,
        Map<String, String> inputs,
        Map<String, String> outputs,
        String h3ClipKey,
        Map<String, Object> provenance,
        List<Map<String, Object>> h3Attempts) {

    public static VideoTypeContext fromJson(ObjectMapper mapper, tools.jackson.databind.JsonNode node) {
        return mapper.treeToValue(node, VideoTypeContext.class);
    }

    public VideoTypeContext withAttempts(List<Map<String, Object>> attempts) {
        return new VideoTypeContext(composeKey, type, sku, candidate, backend, prompt,
                firstFrameKey, sceneLabel, planInput, inputs, outputs, h3ClipKey, provenance,
                attempts);
    }

    public VideoTypeContext withBackend(H3Backend next) {
        return new VideoTypeContext(composeKey, type, sku, candidate, next, prompt,
                firstFrameKey, sceneLabel, planInput, inputs, outputs, h3ClipKey, provenance,
                h3Attempts);
    }

    /** The plan input with the given hook mode; the fallback re-plans with real footage. */
    public VideoPlanner.PlanInput planInput(boolean useH3) {
        return new VideoPlanner.PlanInput(planInput.type(), planInput.sources(), useH3,
                planInput.windows(), planInput.captionCount(), planInput.musicInput());
    }

    /** The real-footage hook window used when H3 is unavailable or has given up. */
    public VideoPlanner.Window fallbackWindow() {
        return planInput.windows().hook();
    }
}