package com.kiano.content.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.ReviewService;
import com.kiano.content.generation.GenerationJob;
import com.kiano.platform.queue.TaskContext;
import com.kiano.workerprotocol.JobStep;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Video review (C5 Task 10): VIDEO_AD sorts after AD_STATIC and VIDEO_SCRIPT
 * after AD_COPY, the review item carries the playback renditions, the cut and
 * the footage preview URLs, and REGENERATE re-plans that type with the next
 * window candidate and the rotated track.
 */
class VideoReviewTest extends VideoTestSupport {

    @Autowired
    private VideoController videoController;

    @Autowired
    private VideoRenderTaskHandler renderTaskHandler;

    /** The video endpoints are role-guarded; the method-security proxy runs in tests too. */
    @org.junit.jupiter.api.BeforeEach
    void authenticate() {
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication
                        .UsernamePasswordAuthenticationToken("video-op@example.test", "n/a",
                        List.of(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void ordering_videosAfterAdStatics_scriptsAfterAdCopy() {
        seedSortableAsset("AD_STATIC", "demo-1080x1080", 1);
        seedSortableAsset("VIDEO_AD", "unboxing", 1);
        seedSortableAsset("VIDEO_AD", "demo", 1);
        seedSortableAsset("VIDEO_AD", "problem", 1);
        seedSortableAsset("AD_COPY", "demo", 1);
        // the fixture already holds one VIDEO_SCRIPT per type

        List<ReviewService.ReviewItem> items = reviewService.list(user, productId, null, null);

        assertThat(items).extracting(ReviewService.ReviewItem::specCode).containsExactly(
                "PAGE_MAIN", "PAGE_SCENE", "AD_STATIC", "VIDEO_AD", "VIDEO_AD", "VIDEO_AD",
                "AD_COPY", "VIDEO_SCRIPT", "VIDEO_SCRIPT", "VIDEO_SCRIPT");
        assertThat(items.stream()
                .filter(item -> "VIDEO_AD".equals(item.specCode()))
                .map(ReviewService.ReviewItem::variant).toList())
                .containsExactly("demo", "problem", "unboxing");
    }

    @Test
    void regenerate_video_usesNextCandidateAndRotatesMusic() {
        long runId = renderService.render(user, productId, Set.of(VideoType.DEMO), 0);
        finishCompose(jobOf(runId, JobStep.VIDEO_COMPOSE, VideoType.DEMO), 27.0);
        AssetEntity asset = videoAssets(VideoType.DEMO).get(0);

        ReviewService.ReviewItem item = reviewService.list(user, productId, null, "VIDEO").stream()
                .filter(candidate -> candidate.assetId() == asset.getId())
                .findFirst().orElseThrow();
        assertThat(item.videoUrl()).contains("v1.mp4");
        assertThat(item.noMusicUrl()).contains("v1_nomusic.mp4");
        assertThat(item.posterUrl()).contains("v1_poster.jpg");
        assertThat(item.durationS()).isEqualByComparingTo("27.00");
        assertThat(item.aiRatio()).isEqualByComparingTo("0.000");
        assertThat(item.edl().path("segments").get(1).path("out").asDouble()).isEqualTo(15.0);
        assertThat(item.sourceUrls()).containsKeys("v1", "v2", "v3");

        clearInvocations(windowPicker, videoProbe, musicPicker);
        ReviewService.DecisionResult result = reviewService.decide(user, asset.getId(),
                ReviewService.Decision.REGENERATE, List.of(), null);

        // the review transaction only queues the work: no downloads, probes or ffmpeg ranking
        assertThat(result.runId()).isNull();
        verifyNoInteractions(windowPicker, videoProbe);
        assertThat(videoAssets(VideoType.DEMO).get(0).getStatus()).isEqualTo("ARCHIVED");
        JsonNode payload = latestRenderTaskPayload();
        assertThat(payload.path("productId").asLong()).isEqualTo(productId);
        assertThat(payload.path("types").toString()).isEqualTo("[\"demo\"]");
        assertThat(payload.path("candidate").asInt()).isEqualTo(1);

        // the queued task plans that type with the next window candidate and the rotated track
        Object outcome = renderTaskHandler.handle(new TaskContext(1L, tenantId, payload, 1));
        long newRun = objectMapper.valueToTree(outcome).path("runId").asLong();
        GenerationJob compose = jobOf(newRun, JobStep.VIDEO_COMPOSE, VideoType.DEMO);
        assertThat(compose.input().path("provenance").path("candidate").asInt()).isEqualTo(1);
        verify(windowPicker, atLeastOnce()).pick(anyList(), eq(1), any());
        verify(musicPicker).pick(eq(tenantId), eq(VideoType.DEMO), eq(1));
    }

    @Test
    void renderEndpoint_checksPreconditionsInline_butPlansInTheBackground() {
        clearInvocations(windowPicker, videoProbe, musicPicker);

        var response = videoController.render(user, productId, Map.of("types", List.of("demo")));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).containsKey("taskId");
        verifyNoInteractions(windowPicker, videoProbe);
        assertThat(latestRenderTaskPayload().path("types").toString()).isEqualTo("[\"demo\"]");

        // a missing precondition is still a synchronous 409, and nothing is queued
        jdbcTemplate.update("delete from music_track");
        when(musicPicker.pick(anyLong(), any(), anyInt())).thenReturn(Optional.empty());
        jdbcTemplate.update("delete from platform_task where tenant_id = ? and type = 'VIDEO_RENDER'",
                tenantId);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        videoController.render(user, productId, Map.of("types", List.of("demo"))))
                .isInstanceOfSatisfying(com.kiano.platform.web.ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("VIDEO_PRECONDITIONS"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from platform_task "
                + "where tenant_id = ? and type = 'VIDEO_RENDER'", Integer.class, tenantId)).isZero();
    }

    @Test
    void renderStatus_reportsTheLatestBackgroundRenderOfTheProduct() {
        assertThat(videoController.renderStatus(user, productId).getStatusCode().value())
                .isEqualTo(204);
        jdbcTemplate.update("insert into platform_task (tenant_id, type, payload, status, last_error, "
                + "finished_at) values (?, 'VIDEO_RENDER', ?::jsonb, 'FAILED', "
                + "'VIDEO_SOURCE_TOO_SHORT: V1 is 6 s', now())",
                tenantId, "{\"productId\":" + productId + "}");

        var status = videoController.renderStatus(user, productId);

        assertThat(status.getStatusCode().value()).isEqualTo(200);
        assertThat(status.getBody().status()).isEqualTo("FAILED");
        assertThat(status.getBody().lastError()).startsWith("VIDEO_SOURCE_TOO_SHORT");
    }

    private JsonNode latestRenderTaskPayload() {
        String payload = jdbcTemplate.queryForObject(
                "select payload from platform_task where tenant_id = ? and type = 'VIDEO_RENDER' "
                        + "order by id desc limit 1", String.class, tenantId);
        return objectMapper.readTree(payload);
    }

    private void seedSortableAsset(String specCode, String variant, int version) {
        jdbcTemplate.update(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json, created_at) "
                        + "values (?, ?, ?, ?, ?, 'IMAGE', 'IN_REVIEW', '{}'::jsonb, '{}'::jsonb, now())",
                tenantId, productId, specCode, variant, version);
    }
}