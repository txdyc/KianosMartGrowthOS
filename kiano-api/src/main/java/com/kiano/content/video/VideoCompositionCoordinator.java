package com.kiano.content.video;

import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.asset.AssetService;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.content.generation.JobCompletionListener;
import com.kiano.content.generation.JobFailureListener;
import com.kiano.content.video.h3.H3Backend;
import com.kiano.content.video.h3.H3BackendSelector;
import com.kiano.content.video.h3.H3TerminalHandler;
import com.kiano.workerprotocol.Edl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives a video type to its composition (C5 Task 9). An H3 clip that succeeds
 * turns the planned hook into the generated clip (kind H3, input h3_hook) and
 * queues the compose; one that times out or fails appends to
 * {@code h3.attempts} and escalates local → API, or falls back to the real
 * footage window with {@code h3Fallback.reason} and an AI ratio of zero. Every
 * path dedupes on the type context's {@code composeKey}, so duplicate terminal
 * signals - including a cancelled local clip reported late - never produce a
 * second VIDEO_COMPOSE job. A finished compose becomes the VIDEO_AD asset.
 */
@Component
public class VideoCompositionCoordinator
        implements JobCompletionListener, JobFailureListener, H3TerminalHandler {

    private final GenerationRunStore runs;
    private final AssetService assets;
    private final ProductCatalog products;
    private final VideoJobFactory jobFactory;
    private final H3BackendSelector h3Selector;
    private final VideoProperties videoProperties;
    private final ObjectMapper objectMapper;

    public VideoCompositionCoordinator(GenerationRunStore runs,
            AssetService assets, ProductCatalog products, VideoJobFactory jobFactory,
            H3BackendSelector h3Selector, VideoProperties videoProperties,
            ObjectMapper objectMapper) {
        this.runs = runs;
        this.assets = assets;
        this.products = products;
        this.jobFactory = jobFactory;
        this.h3Selector = h3Selector;
        this.videoProperties = videoProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onSucceeded(GenerationJob job) {
        switch (job.step()) {
            case H3_CLIP_LOCAL -> onH3Succeeded(job);
            case VIDEO_COMPOSE -> createAsset(job);
            default -> {
            }
        }
    }

    @Override
    public void onFailed(GenerationJob job) {
        switch (job.step()) {
            case H3_CLIP_LOCAL -> onH3Escalate(job, code(job.error(), "H3_LOCAL_FAILED"));
            case H3_CLIP_API -> onH3Escalate(job, code(job.error(), "H3_FAILED"));
            default -> {
            }
        }
    }

    @Override
    public void onH3Succeeded(GenerationJob job) {
        VideoTypeContext context = contextOf(job);
        if (context == null
                || jobFactory.composeExists(job.tenantId(), job.runId(), context.composeKey())) {
            return;
        }
        String clipKey = job.output().path("outputs").path("clip").path("objectKey")
                .asString(context.h3ClipKey());
        Map<String, String> inputs = new LinkedHashMap<>(context.inputs());
        inputs.put("h3_hook", clipKey);
        VideoTypeContext updated = context.withAttempts(
                withAttempt(context.h3Attempts(), context.backend(), "SUCCEEDED", null));
        // the planned EDL already carries the H3 hook (input h3_hook, 0 → hookSeconds)
        Edl edl = VideoPlanner.plan(updated.planInput(true), videoProperties);
        jobFactory.createCompose(job.tenantId(), job.productId(), job.runId(), updated, edl, inputs,
                VideoRenderService.composedProvenance(updated, null));
    }

    @Override
    public void onH3Escalate(GenerationJob job, String reason) {
        VideoTypeContext context = contextOf(job);
        if (context == null
                || jobFactory.composeExists(job.tenantId(), job.runId(), context.composeKey())) {
            return;
        }
        H3Backend failed = context.backend() == null ? H3Backend.API : context.backend();
        String outcome = "H3_LOCAL_TIMEOUT".equals(reason) ? "TIMEOUT" : "FAILED";
        VideoTypeContext updated = context.withAttempts(
                withAttempt(context.h3Attempts(), failed, outcome, reason));
        Optional<H3Backend> next = h3Selector.next(job.tenantId(), failed);
        if (next.isPresent() && next.get() == H3Backend.LOCAL) {
            if (jobFactory.createH3Local(job.tenantId(), job.productId(), job.runId(),
                    updated.withBackend(H3Backend.LOCAL)).isPresent()) {
                return;
            }
        }
        if (next.isPresent()) {
            jobFactory.createH3Api(job.tenantId(), job.productId(), job.runId(),
                    updated.withBackend(next.get()));
            return;
        }
        // real footage instead: the fallback window was already picked at planning
        Edl edl = VideoPlanner.plan(updated.planInput(false), videoProperties);
        jobFactory.createCompose(job.tenantId(), job.productId(), job.runId(), updated, edl,
                updated.inputs(), VideoRenderService.composedProvenance(updated,
                        Map.of("reason", reason)));
    }

    private void createAsset(GenerationJob job) {
        @SuppressWarnings("unchecked")
        Map<String, Object> provenance = objectMapper.treeToValue(
                job.input().path("provenance"), Map.class);
        String sku = products.findById(job.tenantId(), job.productId())
                .map(ProductView::sku).orElse("");
        assets.createVideoFromJob(job, sku, provenance);
        runs.refreshStatus(job.runId());
    }

    private static List<Map<String, Object>> withAttempt(List<Map<String, Object>> attempts,
            @Nullable H3Backend backend, String outcome, @Nullable String reason) {
        List<Map<String, Object>> extended = new ArrayList<>(
                attempts == null ? List.of() : attempts);
        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("backend", backend == null ? null : backend.name().toLowerCase());
        attempt.put("outcome", outcome);
        attempt.put("reason", reason);
        extended.add(attempt);
        return List.copyOf(extended);
    }

    /** The type context of an H3 job; null when the input has none. */
    private @Nullable VideoTypeContext contextOf(GenerationJob job) {
        JsonNode node = job.input().path("typeContext");
        if (!node.isObject() || node.isEmpty()) {
            return null;
        }
        return VideoTypeContext.fromJson(objectMapper, node);
    }

    /** The error's leading code, e.g. {@code H3_TIMEOUT} from {@code H3_TIMEOUT: ...}. */
    private static String code(@Nullable String error, String fallback) {
        if (error == null || error.isBlank()) {
            return fallback;
        }
        int colon = error.indexOf(':');
        String code = colon < 0 ? error : error.substring(0, colon);
        return code.isBlank() ? fallback : code.trim();
    }
}