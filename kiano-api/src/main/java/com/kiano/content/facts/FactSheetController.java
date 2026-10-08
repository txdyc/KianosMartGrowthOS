package com.kiano.content.facts;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.content.facts.FactSheetService.FactSheetView;
import com.kiano.content.media.SourceMediaEntity;
import com.kiano.content.media.SourceMediaMapper;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.queue.TaskQueue;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final TaskQueue queue;

    public FactSheetController(FactSheetService service, SourceMediaMapper sourceMediaMapper,
            ObjectStorage storage, TaskQueue queue) {
        this.service = service;
        this.sourceMediaMapper = sourceMediaMapper;
        this.storage = storage;
        this.queue = queue;
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

    @PostMapping("/draft-from-ai")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> draftFromAi(CurrentUser user,
            @PathVariable("id") long productId) {
        Optional<FactSheetView> draft = service.current(user.tenantId(), productId);
        if (draft.isPresent() && "DRAFT".equals(draft.get().status())) {
            throw new ApiException(HttpStatus.CONFLICT, "FACT_DRAFT_EXISTS",
                    "A fact draft already exists; lock or discard it before generating again");
        }
        if (factSourceCount(user.tenantId(), productId) == 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_FACT_SOURCES",
                    "No accepted P5 or PROMO photos exist for this product");
        }
        Optional<Long> taskId = queue.enqueue(user.tenantId(), FactDraftTaskHandler.TYPE,
                Map.of("productId", productId), "fact-draft:" + productId);
        Map<String, Object> body = new LinkedHashMap<>();
        taskId.ifPresent(id -> body.put("taskId", id));
        body.put("alreadyQueued", taskId.isEmpty());
        return ResponseEntity.accepted().body(body);
    }

    private long factSourceCount(long tenantId, long productId) {
        return sourceMediaMapper.selectCount(Wrappers.<SourceMediaEntity>lambdaQuery()
                .eq(SourceMediaEntity::getTenantId, tenantId)
                .eq(SourceMediaEntity::getProductId, productId)
                .in(SourceMediaEntity::getShotCode, List.of("P5", "PROMO"))
                .eq(SourceMediaEntity::getStatus, "ACCEPTED"));
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