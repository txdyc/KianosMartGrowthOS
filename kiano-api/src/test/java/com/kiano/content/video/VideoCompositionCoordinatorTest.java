package com.kiano.content.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetFileName;
import com.kiano.content.generation.GenerationJob;
import com.kiano.workerprotocol.JobStep;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * VideoCompositionCoordinator (C5 T9): H3 escalation and fallback, compose
 * dedupe, and the VIDEO_AD asset that follows a finished compose - including
 * the AI-ratio gate at completion (Review Focus 4).
 */
class VideoCompositionCoordinatorTest extends VideoTestSupport {

    @Test
    void h3Failed_composeUsesFallbackWindow_recordsReason_ratioZero() {
        h3Properties.getApi().setEnabled(true); // no local workflow: the API is chosen

        long runId = renderService.render(user, productId, Set.of(VideoType.DEMO), 0, null);
        GenerationJob api = jobOf(runId, JobStep.H3_CLIP_API, VideoType.DEMO);

        fail(api, "H3_FAILED: provider gave up");

        GenerationJob compose = jobOf(runId, JobStep.VIDEO_COMPOSE, VideoType.DEMO);
        JsonNode hook = compose.input().path("edl").path("segments").get(0);
        assertThat(hook.path("kind").asString()).isEqualTo("REAL");
        assertThat(hook.path("input").asString()).isEqualTo("v1");
        assertThat(hook.path("in").asDouble()).isZero();
        assertThat(hook.path("out").asDouble()).isEqualTo(3.0);
        JsonNode provenance = compose.input().path("provenance");
        assertThat(provenance.path("h3Fallback").path("reason").asString()).isEqualTo("H3_FAILED");
        assertThat(provenance.path("h3").path("attempts")).hasSize(1);
        assertThat(provenance.path("h3").path("attempts").get(0).path("backend").asString())
                .isEqualTo("api");
        for (JsonNode segment : compose.input().path("edl").path("segments")) {
            assertThat(segment.path("kind").asString()).isNotEqualTo("H3");
        }

        finishCompose(compose, 27.0);

        AssetEntity asset = videoAssets(VideoType.DEMO).get(0);
        assertThat(asset.getAiRatio()).isEqualByComparingTo("0.000");
        assertThat(asset.getFileName()).isEqualTo("MG-KTL17_demo_real_1080x1920_v1.mp4");
    }

    @Test
    void localFailed_escalatesToApi_thenApiFailed_fallsBackToReal() {
        activateH3Workflow();
        h3Properties.getApi().setEnabled(true);

        long runId = renderService.render(user, productId, Set.of(VideoType.DEMO), 0, null);
        GenerationJob local = jobOf(runId, JobStep.H3_CLIP_LOCAL, VideoType.DEMO);

        fail(local, "H3_LOCAL_FAILED: ComfyUI did not finish the clip");

        GenerationJob api = jobOf(runId, JobStep.H3_CLIP_API, VideoType.DEMO);
        // escalation reuses the same plan: prompt, first frame and compose key
        assertThat(api.input().path("prompt").asString())
                .isEqualTo(local.input().path("params").path("positive").asString());
        assertThat(api.input().path("firstFrameKey").asString())
                .isEqualTo(local.input().path("inputs").path("image").asString());
        assertThat(api.input().path("outputs").path("clip").asString())
                .isEqualTo(local.input().path("outputs").path("clip").asString());
        JsonNode escalated = api.input().path("typeContext");
        assertThat(escalated.path("composeKey").asString())
                .isEqualTo(local.input().path("typeContext").path("composeKey").asString());
        assertThat(escalated.path("h3Attempts")).hasSize(1);
        assertThat(escalated.path("h3Attempts").get(0).path("backend").asString())
                .isEqualTo("local");
        assertThat(escalated.path("h3Attempts").get(0).path("outcome").asString())
                .isEqualTo("FAILED");
        assertThat(escalated.path("h3Attempts").get(0).path("reason").asString())
                .isEqualTo("H3_LOCAL_FAILED");

        fail(api, "H3_FAILED: provider gave up");

        GenerationJob compose = jobOf(runId, JobStep.VIDEO_COMPOSE, VideoType.DEMO);
        JsonNode provenance = compose.input().path("provenance");
        assertThat(provenance.path("h3").path("attempts")).hasSize(2);
        assertThat(provenance.path("h3").path("attempts").get(1).path("backend").asString())
                .isEqualTo("api");
        assertThat(provenance.path("h3Fallback").path("reason").asString()).isEqualTo("H3_FAILED");
        assertThat(provenance.path("h3").path("backend").asString()).isEqualTo("API");
        assertThat(compose.input().path("edl").path("segments").get(0).path("kind").asString())
                .isEqualTo("REAL");
    }

    @Test
    void duplicateTerminalSignals_createSingleComposeJob() {
        activateH3Workflow();

        long runId = renderService.render(user, productId, Set.of(VideoType.DEMO), 0, null);
        GenerationJob local = jobOf(runId, JobStep.H3_CLIP_LOCAL, VideoType.DEMO);
        String clipKey = local.input().path("outputs").path("clip").asString();
        storage.put(clipKey, videoBytes(), "video/mp4");

        GenerationJob completed = complete(local, Map.of("outputs", Map.of("clip",
                Map.of("objectKey", clipKey, "durationS", 5.0))));
        // the same terminal signal may arrive again (a late report, a retried callback)
        coordinator.onH3Succeeded(completed);
        coordinator.onH3Succeeded(completed);

        assertThat(runJobs(runId).stream().filter(job -> job.step() == JobStep.VIDEO_COMPOSE))
                .hasSize(1);
    }

    @Test
    void composeSucceeded_createsVideoAsset_withRenditionsFileNameAndPrice() throws Exception {
        long runId = renderService.render(user, productId, Set.of(VideoType.DEMO), 0, null);
        GenerationJob compose = jobOf(runId, JobStep.VIDEO_COMPOSE, VideoType.DEMO);

        finishCompose(compose, 27.0);

        List<AssetEntity> assets = videoAssets(VideoType.DEMO);
        assertThat(assets).hasSize(1);
        AssetEntity asset = assets.get(0);
        assertThat(asset.getStatus()).isEqualTo("IN_REVIEW");
        assertThat(asset.getFileName()).isEqualTo("MG-KTL17_demo_real_1080x1920_v1.mp4");
        assertThat(AssetFileName.rendition(asset.getFileName(), "nomusic"))
                .isEqualTo("MG-KTL17_demo_real_1080x1920_v1_nomusic.mp4");
        assertThat(asset.getDurationS()).isEqualByComparingTo("27.00");
        assertThat(asset.getWidth()).isEqualTo(1080);
        assertThat(asset.getHeight()).isEqualTo(1920);
        assertThat(asset.getAiRatio()).isEqualByComparingTo("0.000");
        assertThat(asset.getDependsOnPrice()).isTrue();
        assertThat(asset.getPriceSnapshot()).isEqualByComparingTo("249.00");
        JsonNode renditions = objectMapper.readTree(asset.getRenditionsJson());
        assertThat(renditions.path("nomusic").asString()).endsWith("/v1_nomusic.mp4");
        assertThat(renditions.path("poster").asString()).endsWith("/v1_poster.jpg");
        assertThat(storage.exists(asset.getObjectKey())).isTrue();
        assertThat(storage.exists(asset.getThumbObjectKey())).isTrue();
        assertThat(storage.exists(renditions.path("nomusic").asString())).isTrue();
        JsonNode provenance = objectMapper.readTree(asset.getProvenanceJson());
        assertThat(provenance.path("musicTrackId").asLong()).isEqualTo(musicTrackId);
        assertThat(provenance.path("scriptAssetId").asLong()).isPositive();
        assertThat(provenance.path("windows").path("body").path("out").asDouble()).isEqualTo(15.0);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'ASSET_CREATED' and target_id = ?",
                Integer.class, String.valueOf(asset.getId()))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from asset where spec_code = 'VIDEO_AD' and status = 'IN_REVIEW'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void newVersion_archivesPreviousInReview() {
        long firstRun = renderService.render(user, productId, Set.of(VideoType.DEMO), 0, null);
        finishCompose(jobOf(firstRun, JobStep.VIDEO_COMPOSE, VideoType.DEMO), 27.0);

        long secondRun = renderService.render(user, productId, Set.of(VideoType.DEMO), 1, null);
        finishCompose(jobOf(secondRun, JobStep.VIDEO_COMPOSE, VideoType.DEMO), 27.0);

        List<AssetEntity> assets = videoAssets(VideoType.DEMO);
        assertThat(assets).hasSize(2);
        assertThat(assets.get(0).getVersion()).isEqualTo(2);
        assertThat(assets.get(0).getStatus()).isEqualTo("IN_REVIEW");
        assertThat(assets.get(1).getVersion()).isEqualTo(1);
        assertThat(assets.get(1).getStatus()).isEqualTo("ARCHIVED");
    }

    @Test
    void aiRatioOver30AtCompletion_assetRejectedNotInReview() throws Exception {
        long runId = renderService.render(user, productId, Set.of(VideoType.DEMO), 0, null);
        GenerationJob compose = jobOf(runId, JobStep.VIDEO_COMPOSE, VideoType.DEMO);

        // a hand-built EDL that slipped past planning: an 8 s AI hook in a 25 s cut is 32 %
        ObjectNode input = (ObjectNode) compose.input();
        ObjectNode edl = (ObjectNode) input.path("edl");
        ObjectNode hook = (ObjectNode) edl.path("segments").get(0);
        hook.put("kind", "H3");
        hook.put("input", "h3_hook");
        hook.put("out", 8.0);
        ((ObjectNode) edl.path("segments").get(1)).put("out", 8.0);
        ((ObjectNode) input.path("inputs")).put("h3_hook",
                "t" + tenantId + "/gen/video/h3_clip.mp4");
        long jobId = jobs.create(tenantId, productId, runId, JobStep.VIDEO_COMPOSE, "demo",
                objectMapper.treeToValue(input, Map.class), List.of());
        GenerationJob handmade = jobs.find(tenantId, jobId).orElseThrow();
        // the planned cut is superseded by the hand-built EDL
        jobs.cancel(tenantId, compose.id(), "EDL superseded in the test");

        finishCompose(handmade, 25.0);

        List<AssetEntity> assets = videoAssets(VideoType.DEMO);
        assertThat(assets).hasSize(1);
        AssetEntity asset = assets.get(0);
        assertThat(asset.getStatus()).isEqualTo("REJECTED");
        assertThat(asset.getAiRatio()).isEqualByComparingTo("0.320");
        assertThat(asset.getFileName()).isEqualTo("MG-KTL17_demo_mixed_1080x1920_v1.mp4");
        JsonNode precheck = objectMapper.readTree(asset.getPrecheckJson());
        assertThat(precheck.path("flags").get(0).asString()).isEqualTo("AI_RATIO_EXCEEDED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'VIDEO_BLOCKED_AI_RATIO' "
                        + "and target_id = ?",
                Integer.class, String.valueOf(asset.getId()))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from asset where spec_code = 'VIDEO_AD' and status = 'IN_REVIEW'",
                Integer.class)).isZero();
    }

    private void fail(GenerationJob job, String error) {
        assertThatLeased(job);
        GenerationJob failed = jobs.fail(job.id(), "test-worker", error, false, false)
                .orElseThrow();
        assertThat(failed.status()).isEqualTo(com.kiano.content.generation.JobStatus.FAILED);
        coordinator.onFailed(failed);
    }
}