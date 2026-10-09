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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ad endpoints (C4a): POST .../ads/copy queues the four-hook LLM copy
 * generation, POST .../ads/render validates the render preconditions (409
 * AD_PRECONDITIONS when unmet) and enqueues the AD_RENDER task (202), and the
 * /ads/exports trio requests, lists and downloads ad export ZIPs plus the
 * replacement list for stale ad platform assets.
 */
@RestController
@RequestMapping("/api/v1/content")
public class AdController {

    /** Render request body: variants optional (null = all twelve). */
    public record RenderRequest(@Nullable List<String> variants, @Nullable Integer frameCandidate,
            boolean priceOnly) {
    }

    /** Export request body: productIds optional (null/empty = all HERO products). */
    public record ExportRequest(@Nullable List<Long> productIds) {
    }

    private final AdRenderService renderService;
    private final AdExportService exportService;
    private final AdReplacementService replacementService;
    private final TaskQueue queue;
    private final FactSheetService factSheetService;
    private final ProductProfileService profileService;

    public AdController(AdRenderService renderService, AdExportService exportService,
            AdReplacementService replacementService, TaskQueue queue,
            FactSheetService factSheetService, ProductProfileService profileService) {
        this.renderService = renderService;
        this.exportService = exportService;
        this.replacementService = replacementService;
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

    @PostMapping("/ads/exports")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> requestExport(CurrentUser user,
            @RequestBody(required = false) ExportRequest request) {
        long publicationId = exportService.request(user,
                request == null ? null : request.productIds());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("publicationId", publicationId));
    }

    @GetMapping("/ads/exports")
    @PreAuthorize("hasRole('VIEWER')")
    public List<AdExportService.ExportView> exports(CurrentUser user) {
        return exportService.list(user.tenantId());
    }

    @GetMapping("/ads/exports/{id}/download")
    @PreAuthorize("hasRole('VIEWER')")
    public ResponseEntity<Void> download(CurrentUser user, @PathVariable("id") long publicationId) {
        java.net.URI uri = exportService.downloadUri(user.tenantId(), publicationId);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(uri).build();
    }

    @GetMapping("/ads/replacements")
    @PreAuthorize("hasRole('VIEWER')")
    public List<AdReplacementService.Replacement> replacements(CurrentUser user) {
        return replacementService.list(user.tenantId());
    }
}
