package com.kiano.content.video;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.commerce.ProductPriceChanged;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.asset.ReviewService;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.JobStatus;
import com.kiano.workerprotocol.JobStep;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Price-driven video re-render (C5 Task 11, Review Focus 5): the latest
 * APPROVED version of every type becomes STALE and only its end card is
 * re-rendered, so the new version auto-approves - unless an EDL edit or a new
 * approved script makes it more than a price change, or the worker is still
 * offline (then the old version stays STALE until the compose completes).
 */
class VideoPriceListenerTest extends VideoTestSupport {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    void priceChange_approvedVideo_staleThenRecomposedAndAutoApproved() {
        AssetEntity v1 = approvedVideo(VideoType.DEMO);
        changePrice(199);

        publishPriceChange();

        assertThat(status(v1.getId())).isEqualTo(AssetStatus.STALE.name());
        GenerationJob recompose = latestCompose(VideoType.DEMO);
        assertThat(recompose.input().path("autoApproveFrom").asLong()).isEqualTo(v1.getId());
        assertThat(audit("VIDEOS_STALE_BY_PRICE")).isEqualTo(1);

        finishCompose(recompose, 27.0);

        List<AssetEntity> versions = videoAssets(VideoType.DEMO);
        assertThat(versions).hasSize(2);
        AssetEntity v2 = versions.get(0);
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2.getStatus()).isEqualTo(AssetStatus.APPROVED.name());
        assertThat(v2.getPriceSnapshot()).isEqualByComparingTo("199.00");
        assertThat(audit("VIDEO_AUTO_APPROVED_PRICE_CHANGE")).isEqualTo(1);
    }

    @Test
    void priceChange_onlyEndCardRerendered_otherInputsReused() {
        AssetEntity v1 = approvedVideo(VideoType.DEMO);
        JsonNode before = objectMapper.readTree(v1.getProvenanceJson());
        changePrice(199);

        publishPriceChange();
        JsonNode input = latestCompose(VideoType.DEMO).input();

        // the cut and every other overlay are the old version's object keys
        assertThat(input.path("edl")).isEqualTo(before.path("edl"));
        for (String key : List.of("cap_hook", "cap_0", "cap_1", "cap_2", "cap_3", "music")) {
            assertThat(input.path("inputs").path(key).asString())
                    .isEqualTo(before.path("inputs").path(key).asString());
        }
        // only the end card is re-rendered: a new key with the new price
        String oldEndCard = before.path("inputs").path("endcard").asString();
        String newEndCard = input.path("inputs").path("endcard").asString();
        assertThat(newEndCard).isNotEqualTo(oldEndCard);
        assertThat(storage.download(newEndCard)).isNotEqualTo(storage.download(oldEndCard));
        assertThat(input.path("provenance").path("price").path("snapshot").asDouble())
                .isEqualTo(199.0);
        assertThat(input.path("provenance").path("musicTrackId").asLong())
                .isEqualTo(musicTrackId);
        assertThat(input.path("provenance").path("scriptAssetId").asLong())
                .isEqualTo(before.path("scriptAssetId").asLong());
    }

    @Test
    void edlEditedOrNewScriptSinceLastRender_newVersionInReview() {
        AssetEntity demo = approvedVideo(VideoType.DEMO);
        AssetEntity problem = approvedVideo(VideoType.PROBLEM);
        // the operator trimmed the demo cut and its version waits in review
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("video-op@example.test", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
        ResponseEntity<Map<String, Object>> response;
        try {
            response = edlController.edit(user, demo.getId(),
                    new VideoEdlController.TrimRequest(List.of(
                            new VideoEdlController.Trim("body", 0, 9))));
        } finally {
            SecurityContextHolder.clearContext();
        }
        long editRun = ((Number) response.getBody().get("runId")).longValue();
        finishCompose(jobOf(editRun, JobStep.VIDEO_COMPOSE, VideoType.DEMO), 21.0);
        assertThat(videoAssets(VideoType.DEMO).get(0).getStatus())
                .isEqualTo(AssetStatus.IN_REVIEW.name());
        // the problem script got a new approved version since its render
        script(VideoType.PROBLEM, 2);
        changePrice(199);

        publishPriceChange();

        finishCompose(latestCompose(VideoType.DEMO), 27.0);
        finishCompose(latestCompose(VideoType.PROBLEM), 27.0);

        assertThat(videoAssets(VideoType.DEMO).get(0).getStatus())
                .isEqualTo(AssetStatus.IN_REVIEW.name());
        assertThat(videoAssets(VideoType.PROBLEM).get(0).getStatus())
                .isEqualTo(AssetStatus.IN_REVIEW.name());
        assertThat(audit("VIDEO_AUTO_APPROVED_PRICE_CHANGE")).isZero();
        assertThat(status(demo.getId())).isEqualTo(AssetStatus.STALE.name());
        assertThat(status(problem.getId())).isEqualTo(AssetStatus.STALE.name());
    }

    @Test
    void workerOffline_staysStaleUntilComposeCompletes() {
        AssetEntity v1 = approvedVideo(VideoType.UNBOXING);
        changePrice(199);

        publishPriceChange();

        // the worker is offline: the compose waits, the old version stays STALE
        GenerationJob recompose = latestCompose(VideoType.UNBOXING);
        assertThat(recompose.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(videoAssets(VideoType.UNBOXING)).hasSize(1);
        assertThat(status(v1.getId())).isEqualTo(AssetStatus.STALE.name());

        finishCompose(recompose, 27.0);

        List<AssetEntity> versions = videoAssets(VideoType.UNBOXING);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getStatus()).isEqualTo(AssetStatus.APPROVED.name());
        assertThat(status(v1.getId())).isEqualTo(AssetStatus.STALE.name());
    }

    // ---- helpers ----

    /** Renders one type, approves it and returns the approved version. */
    private AssetEntity approvedVideo(VideoType type) {
        long runId = renderService.render(user, productId, Set.of(type), 0, null);
        finishCompose(jobOf(runId, JobStep.VIDEO_COMPOSE, type), 27.0);
        AssetEntity asset = videoAssets(type).get(0);
        reviewService.decide(user, asset.getId(), ReviewService.Decision.APPROVE, List.of(),
                null);
        return assetMapper.selectById(asset.getId());
    }

    /** The Woo sync writes the new price before the event is published. */
    private void changePrice(int sale) {
        jdbcTemplate.update("update product set regular_price = 299, sale_price = ?, price = ? "
                + "where id = ?", sale, sale, productId);
    }

    private void publishPriceChange() {
        // AFTER_COMMIT listeners only run when the publish happens in a transaction
        new TransactionTemplate(txManager).executeWithoutResult(status -> events.publishEvent(
                new ProductPriceChanged(tenantId, productId, SKU,
                        BigDecimal.valueOf(299), BigDecimal.valueOf(199),
                        BigDecimal.valueOf(299), BigDecimal.valueOf(199), null, null)));
    }

    /** The newest VIDEO_COMPOSE job of one type (the listener's re-render). */
    private GenerationJob latestCompose(VideoType type) {
        Long runId = jdbcTemplate.queryForObject(
                "select run_id from generation_job where tenant_id = ? and step = 'VIDEO_COMPOSE' "
                        + "and variant = ? order by id desc limit 1",
                Long.class, tenantId, type.wire());
        return jobOf(runId, JobStep.VIDEO_COMPOSE, type);
    }

    private String status(long assetId) {
        return jdbcTemplate.queryForObject("select status from asset where id = ?", String.class,
                assetId);
    }

    private int audit(String action) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = ? and tenant_id = ?", Integer.class,
                action, tenantId);
    }
}