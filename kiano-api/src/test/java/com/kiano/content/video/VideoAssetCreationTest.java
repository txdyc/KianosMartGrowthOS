package com.kiano.content.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.kiano.content.asset.AssetEntity;
import com.kiano.content.generation.GenerationJob;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.workerprotocol.JobStep;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turning a finished compose into the VIDEO_AD asset: the renditions are copied
 * inside the object store (never pulled through the api heap), and a video the
 * AI-ratio gate rejects carries a system review row with the reason code.
 */
class VideoAssetCreationTest extends VideoTestSupport {

    @MockitoSpyBean
    private ObjectStorage spyStorage;

    @Test
    void finishedCompose_copiesTheRenditionsServerSide_withoutDownloadingThem() {
        long runId = renderService.render(user, productId, Set.of(VideoType.DEMO), 0);
        GenerationJob compose = jobOf(runId, JobStep.VIDEO_COMPOSE, VideoType.DEMO);
        String videoKey = compose.input().path("outputs").path("video").asString();
        String noMusicKey = compose.input().path("outputs").path("video_nomusic").asString();
        clearInvocations(spyStorage);

        finishCompose(compose, 27.0);

        AssetEntity asset = videoAssets(VideoType.DEMO).get(0);
        verify(spyStorage, never()).download(videoKey);
        verify(spyStorage, never()).download(noMusicKey);
        verify(spyStorage).copy(eq(videoKey), eq(asset.getObjectKey()), eq("video/mp4"));
        verify(spyStorage).copy(eq(noMusicKey), anyString(), eq("video/mp4"));
        assertThat(spyStorage.download(asset.getObjectKey())).isEqualTo(videoBytes());
    }

    @Test
    void aiRatioBlockedVideo_getsASystemReviewWithTheReasonCode() throws Exception {
        long runId = renderService.render(user, productId, Set.of(VideoType.DEMO), 0);
        GenerationJob compose = jobOf(runId, JobStep.VIDEO_COMPOSE, VideoType.DEMO);
        ObjectNode input = (ObjectNode) compose.input();
        ObjectNode edl = (ObjectNode) input.path("edl");
        ObjectNode hook = (ObjectNode) edl.path("segments").get(0);
        hook.put("kind", "H3");
        hook.put("input", "h3_hook");
        hook.put("out", 8.0);
        ((ObjectNode) edl.path("segments").get(1)).put("out", 8.0);
        ((ObjectNode) input.path("inputs")).put("h3_hook", "t" + tenantId + "/gen/video/h3_clip.mp4");
        long jobId = jobs.create(tenantId, productId, runId, JobStep.VIDEO_COMPOSE, "demo",
                objectMapper.treeToValue(input, Map.class), List.of());
        jobs.cancel(tenantId, compose.id(), "EDL superseded in the test");

        finishCompose(jobs.find(tenantId, jobId).orElseThrow(), 25.0);

        AssetEntity asset = videoAssets(VideoType.DEMO).get(0);
        assertThat(asset.getStatus()).isEqualTo("REJECTED");
        Map<String, Object> review = jdbcTemplate.queryForMap(
                "select reviewer, decision, reason_codes::text as reasons from asset_review "
                        + "where asset_id = ?", asset.getId());
        assertThat(review.get("reviewer")).isNull();
        assertThat(review.get("decision")).isEqualTo("REJECT");
        assertThat(String.valueOf(review.get("reasons"))).contains("AI_RATIO_EXCEEDED");
    }
}
