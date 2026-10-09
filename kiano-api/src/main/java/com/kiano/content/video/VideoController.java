package com.kiano.content.video;

import com.kiano.content.ContentTier;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactSheetService.FactSheetView;
import com.kiano.content.profile.ProductProfileService;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.queue.TaskQueue;
import com.kiano.platform.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Video endpoints (C5): POST .../products/{id}/videos/script queues the
 * three-type LLM script generation (202). Later tasks add the EDL editor,
 * composition trigger and video review endpoints here.
 */
@RestController
@RequestMapping("/api/v1/content")
public class VideoController {

    private final TaskQueue queue;
    private final FactSheetService factSheetService;
    private final ProductProfileService profileService;

    public VideoController(TaskQueue queue, FactSheetService factSheetService,
            ProductProfileService profileService) {
        this.queue = queue;
        this.factSheetService = factSheetService;
        this.profileService = profileService;
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
