package com.kiano.content.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kiano.content.asset.AssetEntity;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.music.MusicPicker;
import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.Edl;
import com.kiano.workerprotocol.JobStep;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        long runId = renderService.render(user, productId, Set.of(VideoType.DEMO), 0);
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
        long runId = renderService.render(user, productId, Set.of(VideoType.DEMO), 0);
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

    @Test
    void editEdl_mixedVideo_recutsTheEditedVersion_keepingH3ClipMusicAndOverlays() {
        long edited = h3Asset(3.0, 15.0, 9, "IN_REVIEW", "v9");
        // a newer version of the same type exists: its overlays must not leak in
        h3Asset(3.0, 15.0, 10, "REJECTED", "v10");
        JsonNode editedProvenance = objectMapper.readTree(
                assetMapper.selectById(edited).getProvenanceJson());
        clearInvocations(windowPicker, videoProbe, musicPicker);
        when(musicPicker.pick(anyLong(), any(), anyInt())).thenReturn(Optional.of(
                new MusicPicker.MusicTrack(musicTrackId + 1000, "Other", "t/music/other.mp3", 30)));

        var response = edlController.edit(user, edited, new VideoEdlController.TrimRequest(
                List.of(new VideoEdlController.Trim("body", 0, 12))));

        long newRun = ((Number) response.getBody().get("runId")).longValue();
        GenerationJob compose = jobOf(newRun, JobStep.VIDEO_COMPOSE, VideoType.DEMO);
        Map<String, String> inputs = composeInputs(compose);
        JsonNode old = editedProvenance.path("inputs");
        // the generated clip, every caption, the end card and the track of THIS version
        for (String key : List.of("h3_hook", "cap_hook", "cap_0", "cap_1", "endcard", "music",
                "v1", "v2")) {
            assertThat(inputs).containsEntry(key, old.path(key).asString());
        }
        assertThat(compose.input().path("provenance").path("musicTrackId").asLong())
                .isEqualTo(musicTrackId);
        assertThat(compose.input().path("provenance").path("h3").path("attempts").size())
                .isEqualTo(1);
        // a recut neither re-plans nor re-picks: no downloads, probes or rotations
        verifyNoInteractions(windowPicker, videoProbe, musicPicker);
    }

    @Test
    void editEdl_boundsUseTheCutsOwnSource_evenAfterAReshoot() {
        long edited = h3Asset(3.0, 15.0);
        // V1 is re-shot afterwards: the newest ACCEPTED V1 is only 12 s long
        jdbcTemplate.update("update source_media set status = 'SUPERSEDED' "
                + "where tenant_id = ? and product_id = ? and shot_code = 'V1'", tenantId, productId);
        jdbcTemplate.update("insert into source_media (tenant_id, product_id, shot_code, kind, "
                        + "original_file_name, object_key, content_type, size_bytes, duration_s, "
                        + "sha256, qc_json, status) values (?, ?, 'V1', 'VIDEO', 'V1-retake.mp4', ?, "
                        + "'video/mp4', 2048, 12.0, ?, '{}', 'ACCEPTED')",
                tenantId, productId, "t" + tenantId + "/source-media/" + productId + "/V1-retake.mp4",
                "b".repeat(64));

        // 0-14 s is longer than the 12 s retake but fits the 30 s source the cut was made from
        var response = edlController.edit(user, edited, new VideoEdlController.TrimRequest(
                List.of(new VideoEdlController.Trim("body", 0, 14))));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void editEdl_sourceDurationUnknown_422InsteadOfSkippingTheBoundsCheck() {
        long edited = h3Asset(3.0, 15.0);
        jdbcTemplate.update("update source_media set duration_s = null "
                + "where tenant_id = ? and product_id = ? and shot_code = 'V1'", tenantId, productId);

        assertThatThrownBy(() -> edlController.edit(user, edited, new VideoEdlController.TrimRequest(
                List.of(new VideoEdlController.Trim("body", 0, 25)))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(violations(ex)).contains("SEGMENT_OUT_OF_SOURCE");
                });
    }

    /** A VIDEO_AD row whose recorded EDL opens with an AI hook. */
    private long h3Asset(double hookSeconds, double bodySeconds) {
        return h3Asset(hookSeconds, bodySeconds, 9, "IN_REVIEW", "v9");
    }

    /**
     * A mixed VIDEO_AD row as a real render records it: EDL with source media
     * ids, the compose inputs (H3 clip, captions, end card, music) and the
     * music track id. {@code tag} makes its object keys unique per version.
     */
    private long h3Asset(double hookSeconds, double bodySeconds, int version, String status,
            String tag) {
        long v1 = shotId("V1");
        long v2 = shotId("V2");
        List<Edl.Segment> segments = List.of(
                new Edl.Segment(Edl.Kind.H3, "hook", "h3_hook", 0, hookSeconds, false, false, null),
                new Edl.Segment(Edl.Kind.REAL, "body", "v1", 0, bodySeconds, true, false, v1),
                new Edl.Segment(Edl.Kind.REAL, "captions", "v2", 0, 5, true, false, v2),
                new Edl.Segment(Edl.Kind.STILL, "endcard", "endcard", 0, 4, false, false, null));
        List<Edl.Overlay> overlays = List.of(
                new Edl.Overlay("cap_hook", 0, hookSeconds),
                new Edl.Overlay("cap_0", hookSeconds, hookSeconds + bodySeconds),
                new Edl.Overlay("cap_1", hookSeconds + bodySeconds, hookSeconds + bodySeconds + 5));
        Edl edl = new Edl("demo", 1080, 1920, segments, overlays, "music",
                new Edl.Audio(0.5, 0.35, 1.5));
        String base = "t" + tenantId + "/video-inputs/" + tag + "/demo/";
        Map<String, String> inputs = new java.util.LinkedHashMap<>();
        inputs.put("v1", "t" + tenantId + "/source-media/" + productId + "/V1.mp4");
        inputs.put("v2", "t" + tenantId + "/source-media/" + productId + "/V2.mp4");
        inputs.put("h3_hook", "t" + tenantId + "/gen/video/" + tag + "/demo/h3_clip.mp4");
        inputs.put("cap_hook", base + "cap_hook.png");
        inputs.put("cap_0", base + "cap_0.png");
        inputs.put("cap_1", base + "cap_1.png");
        inputs.put("endcard", base + "endcard.png");
        inputs.put("music", "t" + tenantId + "/music/" + tag + ".mp3");
        Map<String, Object> provenance = new java.util.LinkedHashMap<>();
        provenance.put("edl", edl);
        provenance.put("candidate", 0);
        provenance.put("musicTrackId", musicTrackId);
        provenance.put("inputs", inputs);
        provenance.put("templates", Map.of("VIDEO_CAPTION", 1, "END_CARD", 1));
        provenance.put("h3", Map.of("attempts", List.of(Map.of("backend", "local",
                "outcome", "SUCCEEDED"))));
        long assetId = jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "object_key, status, precheck_json, provenance_json, ai_ratio, created_at) "
                        + "values (?, ?, 'VIDEO_AD', 'demo', ?, 'VIDEO', ?, ?, "
                        + "'{}'::jsonb, ?::jsonb, 0.15, now()) returning id",
                Long.class, tenantId, productId, version,
                "t" + tenantId + "/assets/" + productId + "/VIDEO_AD/demo/v" + version + ".mp4",
                status, objectMapper.writeValueAsString(provenance));
        return assetId;
    }

    private long shotId(String shot) {
        return jdbcTemplate.queryForObject(
                "select id from source_media where tenant_id = ? and product_id = ? "
                        + "and shot_code = ? order by id limit 1", Long.class,
                tenantId, productId, shot);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> composeInputs(GenerationJob compose) {
        return objectMapper.treeToValue(compose.input().path("inputs"), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<String> violations(ApiException ex) {
        List<Map<String, Object>> violations =
                (List<Map<String, Object>>) ex.getDetails().get("violations");
        return violations.stream().map(v -> String.valueOf(v.get("code"))).toList();
    }
}