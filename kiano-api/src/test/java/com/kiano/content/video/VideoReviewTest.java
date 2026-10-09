package com.kiano.content.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.ReviewService;
import com.kiano.content.generation.GenerationJob;
import com.kiano.workerprotocol.JobStep;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Video review (C5 Task 10): VIDEO_AD sorts after AD_STATIC and VIDEO_SCRIPT
 * after AD_COPY, the review item carries the playback renditions, the cut and
 * the footage preview URLs, and REGENERATE re-plans that type with the next
 * window candidate and the rotated track.
 */
class VideoReviewTest extends VideoTestSupport {

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
        long runId = renderService.render(user, productId, Set.of(VideoType.DEMO), 0, null);
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

        ReviewService.DecisionResult result = reviewService.decide(user, asset.getId(),
                ReviewService.Decision.REGENERATE, List.of(), null);

        assertThat(result.runId()).isNotNull();
        GenerationJob compose = jobOf(result.runId(), JobStep.VIDEO_COMPOSE, VideoType.DEMO);
        assertThat(compose.input().path("provenance").path("candidate").asInt()).isEqualTo(1);
        verify(windowPicker, atLeastOnce()).pick(anyList(), eq(1), any());
        verify(musicPicker).pick(eq(tenantId), eq(VideoType.DEMO), eq(1));
        assertThat(videoAssets(VideoType.DEMO).get(0).getStatus()).isEqualTo("ARCHIVED");
    }

    private void seedSortableAsset(String specCode, String variant, int version) {
        jdbcTemplate.update(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json, created_at) "
                        + "values (?, ?, ?, ?, ?, 'IMAGE', 'IN_REVIEW', '{}'::jsonb, '{}'::jsonb, now())",
                tenantId, productId, specCode, variant, version);
    }
}