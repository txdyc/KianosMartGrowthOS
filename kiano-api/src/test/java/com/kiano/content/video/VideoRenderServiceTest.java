package com.kiano.content.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.kiano.content.generation.GenerationJob;
import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.JobStep;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * VideoRenderService (C5 T9): the preconditions, the three-type compose
 * planning with H3 disabled (the current default), and the local H3 job whose
 * clip turns the hook into AI footage once the worker reports it.
 */
class VideoRenderServiceTest extends VideoTestSupport {

    @Test
    void render_h3Disabled_createsThreeComposeJobs_withEdlInputsOutputs() {
        long runId = renderService.render(user, productId, null, 0, null);

        List<GenerationJob> composeJobs = runJobs(runId).stream()
                .filter(job -> job.step() == JobStep.VIDEO_COMPOSE)
                .toList();
        assertThat(composeJobs).hasSize(3);
        assertThat(composeJobs).extracting(GenerationJob::variant)
                .containsExactlyInAnyOrder("demo", "problem", "unboxing");

        GenerationJob demo = jobOf(runId, JobStep.VIDEO_COMPOSE, VideoType.DEMO);
        JsonNode edl = demo.input().path("edl");
        assertThat(edl.path("videoType").asString()).isEqualTo("demo");
        assertThat(edl.path("width").asDouble()).isEqualTo(1080);
        assertThat(edl.path("height").asDouble()).isEqualTo(1920);
        assertThat(edl.path("segments").get(0).path("kind").asString()).isEqualTo("REAL");
        assertThat(edl.path("segments").get(0).path("role").asString()).isEqualTo("hook");
        assertThat(edl.path("segments").get(0).path("input").asString()).isEqualTo("v1");
        assertThat(edl.path("segments").get(0).path("out").asDouble()).isEqualTo(3.0);
        assertThat(edl.path("segments").get(0).path("hasAudio").asBoolean()).isTrue();
        // demo body clamps to the recipe maximum, the end card is a still
        assertThat(edl.path("segments").get(1).path("out").asDouble()).isEqualTo(15.0);
        assertThat(edl.path("segments").get(3).path("kind").asString()).isEqualTo("STILL");
        assertThat(edl.path("musicInput").asString()).isEqualTo("music");

        assertThat(demo.input().path("inputs").path("v1").asString()).isNotEmpty();
        assertThat(demo.input().path("inputs").path("v2").asString()).isNotEmpty();
        assertThat(demo.input().path("inputs").path("cap_hook").asString())
                .endsWith("/cap_hook.png");
        assertThat(demo.input().path("inputs").path("cap_0").asString()).endsWith("/cap_0.png");
        assertThat(demo.input().path("inputs").path("endcard").asString()).endsWith("/endcard.png");
        assertThat(demo.input().path("inputs").path("music").asString()).endsWith("upbeat.mp3");
        assertThat(demo.input().path("outputs").path("video").asString()).endsWith("/video.mp4");
        assertThat(demo.input().path("outputs").path("video_nomusic").asString())
                .endsWith("/video_nomusic.mp4");
        assertThat(demo.input().path("outputs").path("poster").asString()).endsWith("/poster.jpg");

        JsonNode provenance = demo.input().path("provenance");
        assertThat(provenance.path("scriptAssetId").asLong()).isPositive();
        assertThat(provenance.path("sourceMediaIds").get(0).asLong()).isPositive();
        assertThat(provenance.path("musicTrackId").asLong()).isEqualTo(musicTrackId);
        assertThat(provenance.path("price").path("snapshot").asDouble()).isEqualTo(249.0);
        assertThat(provenance.path("candidate").asInt()).isZero();
        assertThat(provenance.path("windows").path("hook").path("out").asDouble()).isEqualTo(3.0);
        // no H3 backend is available, so the hook is real footage
        assertThat(provenance.path("h3Fallback").path("reason").asString())
                .isEqualTo("H3_NO_BACKEND");
        assertThat(provenance.path("h3").path("attempts")).isEmpty();

        // unboxing cuts V3, demo and problem cut V1
        GenerationJob unboxing = jobOf(runId, JobStep.VIDEO_COMPOSE, VideoType.UNBOXING);
        assertThat(unboxing.input().path("edl").path("segments").get(0).path("input").asString())
                .isEqualTo("v3");
    }

    @Test
    void preconditionsMissing_409WithList() {
        jdbcTemplate.update("delete from source_media where shot_code = 'V3'");
        jdbcTemplate.update(
                "delete from asset where spec_code = 'VIDEO_SCRIPT' and variant = 'unboxing'");
        jdbcTemplate.update("delete from music_track");
        when(musicPicker.pick(anyLong(), any(), anyInt())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> renderService.render(user, productId, null, 0, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(409);
                    assertThat(api.getCode()).isEqualTo("VIDEO_PRECONDITIONS");
                    @SuppressWarnings("unchecked")
                    List<String> missing = (List<String>) api.getDetails().get("missing");
                    assertThat(missing).contains("V3", "VIDEO_SCRIPT:unboxing",
                            "MUSIC_LIBRARY_EMPTY");
                });
    }

    @Test
    void localWorkflowActive_createsLocalH3Jobs_composeAfterSuccess() {
        activateH3Workflow();

        long runId = renderService.render(user, productId, Set.of(VideoType.DEMO), 0, null);

        GenerationJob h3 = jobOf(runId, JobStep.H3_CLIP_LOCAL, VideoType.DEMO);
        assertThat(runJobs(runId).stream().filter(job -> job.step() == JobStep.VIDEO_COMPOSE))
                .isEmpty();
        JsonNode input = h3.input();
        assertThat(input.path("workflow").path("code").asString()).isEqualTo("H3_I2V");
        assertThat(input.path("workflow").path("version").asInt()).isEqualTo(1);
        assertThat(input.path("params").path("positive").asString()).startsWith(
                "For the target video, at 0.00 seconds into the target video,");
        assertThat(input.path("params").path("positive").asString()).contains("Morgan 1.7L Kettle");
        assertThat(input.path("params").path("width").asInt()).isEqualTo(720);
        assertThat(input.path("params").path("height").asInt()).isEqualTo(1280);
        assertThat(input.path("params").path("fps").asInt()).isEqualTo(24);
        assertThat(input.path("params").path("frames").asInt()).isEqualTo(120);
        assertThat(input.path("params").path("seed").asLong()).isNotNegative();
        assertThat(input.path("comfyTimeoutSeconds").asInt()).isEqualTo(1800);
        assertThat(input.path("inputs").path("image").asString())
                .startsWith("t" + tenantId + "/video-inputs/");
        String clipKey = input.path("outputs").path("clip").asString();
        assertThat(clipKey).endsWith("/h3_clip.mp4");
        assertThat(input.path("fallbackWindow").path("out").asDouble()).isEqualTo(3.0);
        assertThat(input.path("typeContext").path("composeKey").asString())
                .isEqualTo("video:" + runId + ":demo");
        assertThat(input.path("typeContext").path("backend").asString()).isEqualTo("LOCAL");
        assertThat(input.path("typeContext").path("inputs").path("cap_hook").asString())
                .isNotEmpty();

        // the local worker finishes the clip; the hook becomes the AI clip
        storage.put(clipKey, videoBytes(), "video/mp4");
        complete(h3, Map.of("outputs", Map.of("clip",
                Map.of("objectKey", clipKey, "durationS", 5.0))));

        GenerationJob compose = jobOf(runId, JobStep.VIDEO_COMPOSE, VideoType.DEMO);
        JsonNode hook = compose.input().path("edl").path("segments").get(0);
        assertThat(hook.path("kind").asString()).isEqualTo("H3");
        assertThat(hook.path("input").asString()).isEqualTo("h3_hook");
        assertThat(hook.path("in").asDouble()).isZero();
        assertThat(hook.path("out").asDouble()).isEqualTo(3.0);
        assertThat(compose.input().path("inputs").path("h3_hook").asString()).isEqualTo(clipKey);
        JsonNode provenance = compose.input().path("provenance");
        assertThat(provenance.path("h3").path("attempts")).hasSize(1);
        assertThat(provenance.path("h3").path("attempts").get(0).path("backend").asString())
                .isEqualTo("local");
        assertThat(provenance.path("h3").path("attempts").get(0).path("outcome").asString())
                .isEqualTo("SUCCEEDED");
        assertThat(provenance.path("h3Fallback")).isEmpty();
    }
}