package com.kiano.content.music;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.video.VideoType;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * MusicPicker (C5 T2): ACTIVE tracks matching the video type's mood win, then
 * least-used-first with rotation; a mood miss falls back to all ACTIVE tracks,
 * and an empty (or fully retired) library returns empty.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MusicPickerTest {

    @Autowired
    private MusicPicker picker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantId;
    private long storeId;
    private long productId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from music_track");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, -8200, 'simple', 'MG-KTL17', 'Kettle 1.7L', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
    }

    private long track(String title, String moods, String status) {
        return jdbcTemplate.queryForObject(
                "insert into music_track (tenant_id, title, artist, object_key, duration_s, "
                        + "moods, license_type, license_source, license_proof, status, sha256) "
                        + "values (?, ?, null, 't" + tenantId + "/music/" + title + ".mp3', 120, "
                        + "?::text[], 'CC0', 'Pixabay', 'https://example.test/proof', ?, ?) "
                        + "returning id",
                Long.class, tenantId, title, "{" + moods + "}", status, "sha-" + title);
    }

    /** Records one VIDEO_AD using the given track (provenance musicTrackId). */
    private void usage(long trackId, int version) {
        jdbcTemplate.update(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json, created_at) values (?, ?, "
                        + "'VIDEO_AD', 'demo', ?, 'VIDEO', 'IN_REVIEW', '{}', ?, now())",
                tenantId, productId, version, "{\"musicTrackId\": " + trackId + "}");
    }

    @Test
    void picker_prefersMoodThenLeastUsed_rotates() {
        long a = track("a", "upbeat", "ACTIVE");
        long b = track("b", "upbeat", "ACTIVE");
        track("c", "calm", "ACTIVE");
        usage(a, 1);
        usage(a, 2);

        Optional<MusicPicker.MusicTrack> first = picker.pick(tenantId, VideoType.DEMO, 0);
        Optional<MusicPicker.MusicTrack> second = picker.pick(tenantId, VideoType.DEMO, 1);

        // demo prefers upbeat: only a and b qualify; b is least used → rotation 0
        assertThat(first).isPresent();
        assertThat(first.get().id()).isEqualTo(b);
        assertThat(second).isPresent();
        assertThat(second.get().id()).isEqualTo(a);
    }

    @Test
    void picker_fallsBackToAllActive_whenNoMoodMatch() {
        long calm = track("calm", "calm", "ACTIVE");

        // demo wants upbeat, none exist → any ACTIVE track qualifies
        assertThat(picker.pick(tenantId, VideoType.DEMO, 0))
                .isPresent().get()
                .extracting(MusicPicker.MusicTrack::id)
                .isEqualTo(calm);
    }

    @Test
    void picker_emptyLibrary_empty() {
        assertThat(picker.pick(tenantId, VideoType.UNBOXING, 0)).isEmpty();
    }

    @Test
    void picker_retiredTrack_notPicked() {
        track("retired", "upbeat", "RETIRED");
        assertThat(picker.pick(tenantId, VideoType.DEMO, 0)).isEmpty();
    }
}
