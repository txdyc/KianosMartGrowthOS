package com.kiano.content.ads;

import com.kiano.content.ContentTier;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactSheetService.FactSheetView;
import com.kiano.content.profile.ProductProfileService;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.queue.TaskQueue;
import com.kiano.platform.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ad endpoints (C4a): POST .../ads/copy queues the four-hook LLM copy
 * generation, POST .../ads/render validates the render preconditions (409
 * AD_PRECONDITIONS when unmet) and enqueues the AD_RENDER task (202).
 */
@RestController
@RequestMapping("/api/v1/content")
public class AdController {

    /** Render request body: variants optional (null = all twelve). */
    public record RenderRequest(@Nullable List<String> variants, @Nullable Integer frameCandidate,
            boolean priceOnly) {
    }

    private final AdRenderService renderService;
    private final TaskQueue queue;
    private final FactSheetService factSheetService;
    private final ProductProfileService profileService;

    public AdController(AdRenderService renderService, TaskQueue queue,
            FactSheetService factSheetService, ProductProfileService profileService) {
        this.renderService = renderService;
        this.queue = queue;
        this.factSheetService = factSheetService;
        this.profileService = profileService;
    }

    @PostMapping("/products/{id}/ads/copy")
    @PreAuthorize("hasRole('OPERATOR')")
    public Map<String, Object> generateCopy(CurrentUser user, @PathVariable("id") long productId,
            @RequestBody(required = false) Map<String, Object> body) {
        long tenantId = user.tenantId();
        if (profileService.tierOf(tenantId, productId) != ContentTier.HERO) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_HERO",
                    "Ad copy is only generated for HERO products");
        }
        FactSheetView locked = factSheetService.locked(tenantId, productId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "FACTS_NOT_LOCKED",
                        "Facts must be locked before ad copy generation"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId);
        payload.put("factVersion", locked.version());
        String onlyHook = "";
        if (body != null && body.get("onlyHook") != null) {
            onlyHook = body.get("onlyHook").toString();
            try {
                AdHook.fromWire(onlyHook);
            } catch (IllegalArgumentException ex) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                        "Unknown ad hook " + onlyHook);
            }
            payload.put("onlyHook", onlyHook);
        }
        queue.enqueue(tenantId, AdCopyTaskHandler.TYPE, payload,
                "ad-copy:" + productId + ":" + locked.version() + ":" + onlyHook);
        return Map.of("enqueued", true, "type", AdCopyTaskHandler.TYPE);
    }

    @PostMapping("/products/{id}/ads/render")
    @PreAuthorize("hasRole('OPERATOR')")
    public Map<String, Object> render(CurrentUser user, @PathVariable("id") long productId,
            @RequestBody(required = false) RenderRequest request) {
        long tenantId = user.tenantId();
        boolean priceOnly = request != null && request.priceOnly();
        renderService.checkPreconditions(tenantId, productId, priceOnly);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId);
        if (request != null && request.variants() != null) {
            payload.put("variants", request.variants());
        }
        payload.put("frameCandidate", request == null || request.frameCandidate() == null
                ? 0 : request.frameCandidate());
        payload.put("priceOnly", priceOnly);
        queue.enqueue(tenantId, AdRenderTaskHandler.TYPE, payload, dedupeKey(productId, payload));
        return Map.of("enqueued", true, "type", AdRenderTaskHandler.TYPE);
    }

    private static String dedupeKey(long productId, Map<String, Object> payload) {
        Object variants = payload.get("variants");
        @SuppressWarnings("unchecked")
        String key = variants instanceof List<?> list && !list.isEmpty()
                ? String.join(",", (List<String>) list) : "all";
        return "ad-render:" + productId + ":" + key + ":" + payload.get("frameCandidate")
                + ":" + payload.get("priceOnly");
    }
}
