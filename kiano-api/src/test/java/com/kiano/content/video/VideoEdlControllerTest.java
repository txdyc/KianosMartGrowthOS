package com.kiano.content.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.content.asset.AssetEntity;
import com.kiano.content.generation.GenerationJob;
import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.Edl;
import com.kiano.workerprotocol.JobStep;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;

/**
 * VideoEdlController (C5 Task 10): a valid trim re-composes the cut with the
 * previous overlays and music, and invalid trims are rejected with all
 * violations - including an AI ratio that exceeds the gate and an out point
 * beyond the source. H3 clips and stills cannot be edited.
 */
class VideoEdlControllerTest extends VideoTestSupport {

    /** The endpoint is OPERATOR-only; the security filter chain runs in tests too. */
    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("video-op@example.test", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void editEdl_validTrim_enqueuesComposeReusingOverlaysAndMusic() {
        long runId = renderService.render(user, productId, Set.of(VideoType.DEMO), 0, null);
        finishCompose(jobOf(runId, JobStep.VIDEO_COMPOSE, VideoType.DEMO), 27.0);
        AssetEntity asset = videoAssets(VideoType.DEMO).get(0);
        JsonNode oldInputs = objectMapper.readTree(asset.getProvenanceJson()).path("inputs");

        var response = edlController.edit(user, asset.getId(),
                new VideoEdlController.TrimRequest(
                        List.of(new VideoEdlController.Trim("body", 0, 10))));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        long newRun = ((Number) response.getBody().get("runId")).longValue();

        GenerationJob compose = jobOf(newRun, JobStep.VIDEO_COMPOSE, VideoType.DEMO);
        JsonNode edl = compose.input().path("edl");
        assertThat(edl.path("segments").get(1).path("out").asDouble()).isEqualTo(10.0);
        // overlay times follow the trimmed lengths: hook 3 s, body 10 s, 3 caption slots
        assertThat(edl.path("overlays").get(0).path("end").asDouble()).isEqualTo(3.0);
        assertThat(edl.path("overlays").get(1).path("start").asDouble()).isEqualTo(3.0);
        assertThat(edl.path("overlays").get(1).path("end").asDouble()).isEqualTo(13.0);
        assertThat(edl.path("overlays").get(2).path("start").asDouble()).isEqualTo(13.0);
        assertThat(edl.path("overlays").get(4).path("end").asDouble()).isEqualTo(18.0);
        // the previous PNGs and the same track are reused
        assertThat(compose.input().path("inputs").path("cap_hook").asString())
                .isEqualTo(oldInputs.path("cap_hook").asString());
        assertThat(compose.input().path("inputs").path("music").asString())
                .isEqualTo(oldInputs.path("music").asString());
        assertThat(compose.input().path("inputs").path("endcard").asString())
                .isEqualTo(oldInputs.path("endcard").asString());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'VIDEO_EDL_EDITED' and target_id = ?",
                Integer.class, String.valueOf(asset.getId()))).isEqualTo(1);
    }

    @Test
    void editEdl_aiOver30_or_outBeyondSource_422WithViolations() {
        long runId = renderService.render(user, productId, Set.of(VideoType.DEMO), 0, null);
        finishCompose(jobOf(runId, JobStep.VIDEO_COMPOSE, VideoType.DEMO), 27.0);
        AssetEntity asset = videoAssets(VideoType.DEMO).get(0);

        // V1 lasts 30 s: an out point of 34 s is outside the source
        assertThatThrownBy(() -> edlController.edit(user, asset.getId(),
                new VideoEdlController.TrimRequest(
                        List.of(new VideoEdlController.Trim("body", 20, 34)))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(violations(ex)).contains("SEGMENT_OUT_OF_SOURCE");
                });

        // a cut with an 8 s AI hook whose body is trimmed to 8 s is 32 % AI
        long h3AssetId = h3Asset(8.0, 8.0);
        assertThatThrownBy(() -> edlController.edit(user, h3AssetId,
                new VideoEdlController.TrimRequest(
                        List.of(new VideoEdlController.Trim("body", 0, 8)))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(violations(ex)).contains("AI_RATIO_EXCEEDED");
                });
    }

    @Test
    void editEdl_cannotChangeH3OrStill_422() {
        long h3AssetId = h3Asset(3.0, 15.0);

        assertThatThrownBy(() -> edlController.edit(user, h3AssetId,
                new VideoEdlController.TrimRequest(
                        List.of(new VideoEdlController.Trim("hook", 0, 2)))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("SEGMENT_NOT_EDITABLE");
                });

        assertThatThrownBy(() -> edlController.edit(user, h3AssetId,
                new VideoEdlController.TrimRequest(
                        List.of(new VideoEdlController.Trim("endcard", 0, 2)))))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SEGMENT_NOT_EDITABLE"));
    }

    /** A VIDEO_AD row whose recorded EDL opens with an AI hook. */
    private long h3Asset(double hookSeconds, double bodySeconds) {
        List<Edl.Segment> segments = List.of(
                new Edl.Segment(Edl.Kind.H3, "hook", "h3_hook", 0, hookSeconds, false, false, null),
                new Edl.Segment(Edl.Kind.REAL, "body", "v1", 0, bodySeconds, true, false, null),
                new Edl.Segment(Edl.Kind.REAL, "captions", "v2", 0, 5, true, false, null),
                new Edl.Segment(Edl.Kind.STILL, "endcard", "endcard", 0, 4, false, false, null));
        List<Edl.Overlay> overlays = List.of(
                new Edl.Overlay("cap_hook", 0, hookSeconds),
                new Edl.Overlay("cap_0", hookSeconds, hookSeconds + bodySeconds),
                new Edl.Overlay("cap_1", hookSeconds + bodySeconds, hookSeconds + bodySeconds + 5));
        Edl edl = new Edl("demo", 1080, 1920, segments, overlays, "music",
                new Edl.Audio(0.5, 0.35, 1.5));
        Map<String, Object> provenance = new java.util.LinkedHashMap<>();
        provenance.put("edl", edl);
        provenance.put("candidate", 0);
        provenance.put("musicTrackId", musicTrackId);
        long assetId = jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "object_key, status, precheck_json, provenance_json, ai_ratio, created_at) "
                        + "values (?, ?, 'VIDEO_AD', 'demo', 9, 'VIDEO', ?, 'IN_REVIEW', "
                        + "'{}'::jsonb, ?::jsonb, 0.15, now()) returning id",
                Long.class, tenantId, productId,
                "t" + tenantId + "/assets/" + productId + "/VIDEO_AD/demo/v9.mp4",
                objectMapper.writeValueAsString(provenance));
        return assetId;
    }

    @SuppressWarnings("unchecked")
    private static List<String> violations(ApiException ex) {
        List<Map<String, Object>> violations =
                (List<Map<String, Object>>) ex.getDetails().get("violations");
        return violations.stream().map(v -> String.valueOf(v.get("code"))).toList();
    }
}