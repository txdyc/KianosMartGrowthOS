package com.kiano.content.video;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.media.SourceMediaEntity;
import com.kiano.content.media.SourceMediaMapper;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.Edl;
import com.kiano.workerprotocol.Edl.Segment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The EDL editor endpoint (C5 Task 10): PUT /assets/{id}/edl applies manual
 * in/out trims to the REAL segments of a video, re-derives the caption overlay
 * times from the new segment lengths, re-validates the cut (422 with all
 * violations) and re-composes it as a new version - reusing the previous
 * overlays and music. H3 clips, stills and overlays cannot be edited; published
 * or archived videos are 409 ASSET_NOT_EDITABLE.
 */
@RestController
@RequestMapping("/api/v1/content")
public class VideoEdlController {

    private static final List<String> REAL_ROLES = List.of("hook", "body", "captions");

    private final AssetMapper assetMapper;
    private final SourceMediaMapper sourceMediaMapper;
    private final VideoRenderService renderService;
    private final VideoProperties videoProperties;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;

    public VideoEdlController(AssetMapper assetMapper, SourceMediaMapper sourceMediaMapper,
            VideoRenderService renderService, VideoProperties videoProperties, AuditLog auditLog,
            ObjectMapper objectMapper) {
        this.assetMapper = assetMapper;
        this.sourceMediaMapper = sourceMediaMapper;
        this.renderService = renderService;
        this.videoProperties = videoProperties;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
    }

    /** One requested trim: the segment role with its new in/out timecodes. */
    public record Trim(String role, double in, double out) {
    }

    public record TrimRequest(List<Trim> segments) {
    }

    @PutMapping("/assets/{id}/edl")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Map<String, Object>> edit(CurrentUser user,
            @PathVariable("id") long assetId, @RequestBody TrimRequest request) {
        AssetEntity asset = assetMapper.selectOne(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, user.tenantId())
                .eq(AssetEntity::getId, assetId));
        if (asset == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                    "Asset " + assetId + " does not exist");
        }
        if (!"VIDEO_AD".equals(asset.getSpecCode())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "Asset " + assetId + " is not a video");
        }
        String status = asset.getStatus();
        if (AssetStatus.PUBLISHED.name().equals(status) || AssetStatus.ARCHIVED.name().equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "ASSET_NOT_EDITABLE",
                    "Asset " + assetId + " is " + status);
        }
        JsonNode provenance = objectMapper.readTree(asset.getProvenanceJson());
        if (!provenance.path("edl").isObject()) {
            throw new ApiException(HttpStatus.CONFLICT, "ASSET_NOT_EDITABLE",
                    "Asset " + assetId + " has no EDL to edit");
        }
        Edl original = objectMapper.treeToValue(provenance.path("edl"), Edl.class);
        Edl trimmed = apply(original, request == null ? null : request.segments());

        List<EdlValidator.Violation> violations = EdlValidator.validate(trimmed, videoProperties,
                inputDurations(trimmed, asset));
        if (!violations.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, violations.get(0).code(),
                    "EDL_INVALID: " + violations.get(0).code(),
                    Map.of("violations", violations.stream()
                            .map(v -> Map.of("code", v.code(), "details", v.details())).toList()));
        }

        long runId = renderService.recut(user, asset, trimmed);
        auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                String.valueOf(user.userId()), "VIDEO_EDL_EDITED", "asset",
                String.valueOf(assetId), before(original), before(trimmed), null, "VIDEO"));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("runId", runId));
    }

    /** The trimmed cut: REAL in/out replaced, overlays re-derived, everything else kept. */
    private static Edl apply(Edl edl, @Nullable List<Trim> trims) {
        Map<String, Trim> byRole = new LinkedHashMap<>();
        if (trims != null) {
            for (Trim trim : trims) {
                if (trim == null || trim.role() == null) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                            "Every segment needs a role");
                }
                Edl.Segment target = segment(edl, trim.role());
                if (target == null || !REAL_ROLES.contains(trim.role())
                        || target.kind() != Edl.Kind.REAL) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SEGMENT_NOT_EDITABLE",
                            "Segment " + trim.role() + " is not editable footage",
                            Map.of("role", trim.role()));
                }
                byRole.put(trim.role(), trim);
            }
        }
        List<Edl.Segment> segments = new ArrayList<>();
        for (Edl.Segment segment : edl.segments()) {
            Trim trim = segment.kind() == Edl.Kind.REAL ? byRole.get(segment.role()) : null;
            segments.add(trim == null ? segment
                    : new Edl.Segment(segment.kind(), segment.role(), segment.input(), trim.in(),
                            trim.out(), segment.hasAudio(), segment.blurFill(),
                            segment.sourceMediaId()));
        }
        return new Edl(edl.videoType(), edl.width(), edl.height(), segments,
                overlays(edl, segments), edl.musicInput(), edl.audio());
    }

    /**
     * The planner's overlay rule over the trimmed lengths: the hook caption
     * covers the hook, the first selling point the body and the remaining
     * captions split the captions segment into the same number of slots.
     */
    private static List<Edl.Overlay> overlays(Edl edl, List<Edl.Segment> segments) {
        double hook = length(segments.get(0));
        double body = length(segments.get(1));
        double captions = length(segments.get(2));
        int slots = (int) edl.overlays().stream()
                .filter(overlay -> overlay.input().startsWith("cap_")
                        && !"cap_hook".equals(overlay.input()) && !"cap_0".equals(overlay.input()))
                .count();
        int count = Math.max(1, slots);
        List<Edl.Overlay> overlays = new ArrayList<>();
        overlays.add(new Edl.Overlay("cap_hook", 0, hook));
        overlays.add(new Edl.Overlay("cap_0", hook, hook + body));
        double slot = captions / count;
        for (int i = 1; i <= count; i++) {
            overlays.add(new Edl.Overlay("cap_" + i, hook + body + (i - 1) * slot,
                    hook + body + i * slot));
        }
        return overlays;
    }

    private static double length(Edl.Segment segment) {
        return segment.out() - segment.in();
    }

    private static @Nullable Segment segment(Edl edl, String role) {
        for (Edl.Segment segment : edl.segments()) {
            if (segment.role().equals(role)) {
                return segment;
            }
        }
        return null;
    }

    /**
     * Source lengths per input key for the segment-bounds check: the footage the
     * cut was made from (the segment's sourceMediaId), not whatever is the newest
     * ACCEPTED take of that shot now. A REAL segment whose length is unknown is
     * left out, which EdlValidator reports as SEGMENT_OUT_OF_SOURCE.
     */
    private Map<String, Double> inputDurations(Edl edl, AssetEntity asset) {
        Map<String, Double> durations = new LinkedHashMap<>();
        for (Edl.Segment segment : edl.segments()) {
            if (segment.kind() == Edl.Kind.H3) {
                // generated clips are fixed length; the segment is validated by the AI ratio
                continue;
            } else if (segment.kind() == Edl.Kind.STILL) {
                durations.put(segment.input(), segment.out());
            } else {
                SourceMediaEntity media = segment.sourceMediaId() != null
                        ? sourceMediaMapper.selectOne(Wrappers.<SourceMediaEntity>lambdaQuery()
                                .eq(SourceMediaEntity::getTenantId, asset.getTenantId())
                                .eq(SourceMediaEntity::getId, segment.sourceMediaId()))
                        : sourceMediaMapper.selectOne(Wrappers.<SourceMediaEntity>lambdaQuery()
                                .eq(SourceMediaEntity::getTenantId, asset.getTenantId())
                                .eq(SourceMediaEntity::getProductId, asset.getProductId())
                                .eq(SourceMediaEntity::getShotCode,
                                        segment.input().toUpperCase(Locale.ROOT))
                                .eq(SourceMediaEntity::getKind, "VIDEO")
                                .eq(SourceMediaEntity::getStatus, "ACCEPTED")
                                .orderByDesc(SourceMediaEntity::getId)
                                .last("limit 1"));
                if (media != null && media.getDurationS() != null) {
                    durations.put(segment.input(), media.getDurationS().doubleValue());
                }
            }
        }
        return durations;
    }

    private static Map<String, Object> before(Edl edl) {
        List<Map<String, Object>> segments = new ArrayList<>();
        for (Edl.Segment segment : edl.segments()) {
            segments.add(Map.of("role", segment.role(), "in", segment.in(), "out", segment.out()));
        }
        return Map.of("segments", segments);
    }
}