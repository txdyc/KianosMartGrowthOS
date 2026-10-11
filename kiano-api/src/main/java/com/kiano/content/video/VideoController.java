package com.kiano.content.video;

import com.kiano.content.ContentTier;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactSheetService.FactSheetView;
import com.kiano.content.profile.ProductProfileService;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.queue.TaskQueue;
import com.kiano.platform.web.ApiException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Video endpoints (C5): POST .../products/{id}/videos/script queues the
 * three-type LLM script generation (202); POST .../videos/render checks the
 * preconditions and queues the background VIDEO_RENDER task (202), whose
 * outcome GET .../videos/render-status reports. The EDL editor and video
 * review live in VideoEdlController and ReviewController.
 */
@RestController
@RequestMapping("/api/v1/content")
public class VideoController {

    private final TaskQueue queue;
    private final FactSheetService factSheetService;
    private final ProductProfileService profileService;
    private final VideoRenderService renderService;

    public VideoController(TaskQueue queue, FactSheetService factSheetService,
            ProductProfileService profileService, VideoRenderService renderService) {
        this.queue = queue;
        this.factSheetService = factSheetService;
        this.profileService = profileService;
        this.renderService = renderService;
    }

    /**
     * Queues the video render of one product (C5 T9): all three types unless
     * {@code types} narrows it. Preconditions are checked synchronously, so a
     * missing script, footage, page image, badge or track is a 409
     * VIDEO_PRECONDITIONS with the items in {@code details.missing}.
     */
    @PostMapping("/products/{id}/videos/render")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> render(CurrentUser user,
            @PathVariable("id") long productId,
            @RequestBody(required = false) Map<String, Object> body) {
        Set<VideoType> types = new LinkedHashSet<>();
        Object requested = body == null ? null : body.get("types");
        if (requested instanceof List<?> list) {
            for (Object item : list) {
                try {
                    types.add(VideoType.fromWire(String.valueOf(item)));
                } catch (IllegalArgumentException ex) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                            "Unknown video type " + item);
                }
            }
        }
        int candidate = 0;
        Object candidateValue = body == null ? null : body.get("candidate");
        if (candidateValue instanceof Number number) {
            candidate = number.intValue();
        }
        long taskId = renderService.queueRender(user, productId, types, candidate);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("taskId", taskId));
    }

    /** Outcome of the product's latest VIDEO_RENDER task (planning runs in the background). */
    public record RenderStatus(String status, @Nullable Long runId, @Nullable String lastError,
            java.time.@Nullable Instant finishedAt) {
    }

    /**
     * The latest background video render of the product, so a render that failed
     * after the request returned (footage too short, a precondition that changed
     * meanwhile) is visible in the videos panel; 204 when none ran yet.
     */
    @GetMapping("/products/{id}/videos/render-status")
    @PreAuthorize("hasRole('VIEWER')")
    public ResponseEntity<RenderStatus> renderStatus(CurrentUser user,
            @PathVariable("id") long productId) {
        return queue.latestFor(user.tenantId(), VideoRenderTaskHandler.TYPE, "productId", productId)
                .map(task -> ResponseEntity.ok(new RenderStatus(task.status().name(),
                        task.result() != null && task.result().path("runId").isNumber()
                                ? task.result().path("runId").asLong() : null,
                        task.lastError(), task.finishedAt())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/products/{id}/videos/script")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> generateScript(CurrentUser user,
            @PathVariable("id") long productId,
            @RequestBody(required = false) Map<String, Object> body) {
        long tenantId = user.tenantId();
        if (profileService.tierOf(tenantId, productId) != ContentTier.HERO) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_HERO",
                    "Video scripts are only generated for HERO products");
        }
        FactSheetView locked = factSheetService.locked(tenantId, productId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "FACTS_NOT_LOCKED",
                        "Facts must be locked before video script generation"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId);
        payload.put("factVersion", locked.version());
        String onlyType = "";
        if (body != null && body.get("onlyType") != null) {
            onlyType = body.get("onlyType").toString();
            try {
                VideoType.fromWire(onlyType);
            } catch (IllegalArgumentException ex) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                        "Unknown video type " + onlyType);
            }
            payload.put("onlyType", onlyType);
        }
        queue.enqueue(tenantId, VideoScriptTaskHandler.TYPE, payload,
                "video-script:" + productId + ":" + locked.version() + ":" + onlyType);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("enqueued", true, "type", VideoScriptTaskHandler.TYPE));
    }
}
