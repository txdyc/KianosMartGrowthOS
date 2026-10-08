package com.kiano.content.facts;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.content.facts.FactSheetService.FactSheetView;
import com.kiano.content.media.SourceMediaEntity;
import com.kiano.content.media.SourceMediaMapper;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.storage.ObjectStorage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fact sheet endpoints: the G2 review page data (draft/locked plus P5 and
 * PROMO source image URLs, VIEWER), draft saving (OPERATOR) and the lock
 * flow with the six-item confirmation checklist (OPERATOR).
 */
@RestController
@RequestMapping("/api/v1/content/products/{id}/facts")
public class FactSheetController {

    private static final Duration URL_TTL = Duration.ofMinutes(15);
    private static final List<String> FACT_SOURCE_SHOTS = List.of("P5", "PROMO");

    private final FactSheetService service;
    private final SourceMediaMapper sourceMediaMapper;
    private final ObjectStorage storage;

    public FactSheetController(FactSheetService service, SourceMediaMapper sourceMediaMapper,
            ObjectStorage storage) {
        this.service = service;
        this.sourceMediaMapper = sourceMediaMapper;
        this.storage = storage;
    }

    public record SaveDraftRequest(@Valid FactsJson facts,
            @NotEmpty Map<String, FieldSource> fieldSources) {
    }

    public record LockRequest(int draftVersion, Set<String> confirmedFields) {
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public Map<String, Object> get(CurrentUser user, @PathVariable("id") long productId) {
        Map<String, Object> body = new LinkedHashMap<>();
        service.current(user.tenantId(), productId)
                .ifPresent(view -> body.put("current", view));
        service.locked(user.tenantId(), productId)
                .ifPresent(view -> body.put("locked", view));
        body.put("sources", sourceUrls(user.tenantId(), productId));
        return body;
    }

    @PutMapping("/draft")
    @PreAuthorize("hasRole('OPERATOR')")
    public FactSheetView saveDraft(CurrentUser user, @PathVariable("id") long productId,
            @Valid @RequestBody SaveDraftRequest request) {
        return service.saveDraft(user, productId, request.facts(), request.fieldSources());
    }

    @PostMapping("/lock")
    @PreAuthorize("hasRole('OPERATOR')")
    public FactSheetView lock(CurrentUser user, @PathVariable("id") long productId,
            @RequestBody LockRequest request) {
        Set<String> confirmed = request.confirmedFields() == null ? Set.of()
                : new LinkedHashSet<>(request.confirmedFields());
        return service.lock(user, productId, request.draftVersion(), confirmed);
    }

    /** Latest accepted P5 and PROMO media as presigned URLs for side-by-side review. */
    private Map<String, Object> sourceUrls(long tenantId, long productId) {
        Map<String, Object> sources = new LinkedHashMap<>();
        for (String shotCode : FACT_SOURCE_SHOTS) {
            List<SourceMediaEntity> media = sourceMediaMapper.selectList(
                    Wrappers.<SourceMediaEntity>lambdaQuery()
                            .eq(SourceMediaEntity::getTenantId, tenantId)
                            .eq(SourceMediaEntity::getProductId, productId)
                            .eq(SourceMediaEntity::getShotCode, shotCode)
                            .eq(SourceMediaEntity::getStatus, "ACCEPTED")
                            .orderByDesc(SourceMediaEntity::getUploadedAt)
                            .last("limit 1"));
            if (media.isEmpty()) {
                continue;
            }
            SourceMediaEntity latest = media.get(0);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", latest.getId());
            info.put("url", storage.presignGet(latest.getObjectKey(), URL_TTL).toString());
            sources.put(shotCode, info);
        }
        return sources;
    }
}