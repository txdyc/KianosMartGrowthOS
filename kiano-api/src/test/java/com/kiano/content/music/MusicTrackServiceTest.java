package com.kiano.content.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * MusicTrackService (C5 T2): licensed upload with ffprobe duration, license
 * validation, sha256 dedupe, and retire (which keeps the object).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MusicTrackServiceTest {

    @Autowired
    private MusicTrackService service;

    @Autowired
    private MusicPicker picker;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;
    private long storeId;
    private long productId;
    private CurrentUser owner;
    private CurrentUser operator;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from music_track");
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, -8100, 'simple', 'MG-KTL17', 'Kettle 1.7L', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
        long ownerId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'music-owner@example.test', 'Owner', ?, 'OWNER') returning id",
                Long.class, tenantId, passwordEncoder.encode("owner-pass-123"));
        owner = new CurrentUser(ownerId, tenantId, Role.OWNER, "music-owner@example.test");
        long operatorId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'music-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        operator = new CurrentUser(operatorId, tenantId, Role.OPERATOR, "music-op@example.test");
    }

    private static MusicTrackService.MusicMeta meta(String licenseType, String licenseProof) {
        return new MusicTrackService.MusicMeta("Summer Groove", "Artist", licenseType,
                "Pixabay Content License", licenseProof, List.of("upbeat"));
    }

    private static MockMultipartFile bytes(String name, byte[] content) {
        return new MockMultipartFile("file", name, "audio/mpeg", content);
    }

    static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").start();
            return p.waitFor() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private static Path sine(String name, int durationSeconds) throws Exception {
        Path dir = Files.createTempDirectory("music-test");
        Path out = dir.resolve(name);
        Process process = new ProcessBuilder("ffmpeg", "-y", "-v", "error", "-f", "lavfi",
                "-i", "sine=frequency=440:duration=" + durationSeconds,
                "-c:a", "pcm_s16le", out.toString())
                .redirectErrorStream(true).start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("ffmpeg sine generation failed");
        }
        return out;
    }

    @Test
    void upload_requiresLicenseFields_andWhitelistedType() {
        byte[] dummy = "not-a-real-audio".getBytes(StandardCharsets.UTF_8);

        // CC_BY needs attribution and is not allowed in ads → 422
        assertThatThrownBy(() -> service.upload(owner, bytes("a.mp3", dummy),
                meta("CC_BY", "https://example.test/proof")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("VALIDATION_FAILED");
                });
        // license proof is mandatory → 422
        assertThatThrownBy(() -> service.upload(owner, bytes("a.mp3", dummy),
                meta("CC0", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_FAILED"));
        // an unknown file type is rejected before probing → 422
        assertThatThrownBy(() -> service.upload(owner, bytes("a.ogg", dummy),
                meta("CC0", "https://example.test/proof")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_FAILED"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from music_track",
                Integer.class)).isZero();
    }

    @EnabledIf("ffmpegAvailable")
    @Test
    void upload_rejectsDuplicateSha_andTooShortAudio() throws Exception {
        Path twenty = sine("twenty.wav", 20);
        byte[] content = Files.readAllBytes(twenty);

        MusicTrackService.MusicTrackView first = service.upload(owner,
                bytes("twenty.wav", content), meta("CC0", "https://example.test/proof"));

        assertThat(first.title()).isEqualTo("Summer Groove");
        assertThat(first.durationSeconds()).isBetween(19.0, 21.0);
        assertThat(first.status()).isEqualTo("ACTIVE");
        assertThat(first.licenseType()).isEqualTo("CC0");

        // same bytes again → 409 DUPLICATE_TRACK
        assertThatThrownBy(() -> service.upload(owner, bytes("twenty.wav", content),
                meta("CC0", "https://example.test/proof")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("DUPLICATE_TRACK");
                });

        // 5 seconds is below the 10s minimum → 422
        Path shortOne = sine("short.wav", 5);
        assertThatThrownBy(() -> service.upload(owner, bytes("short.wav",
                Files.readAllBytes(shortOne)), meta("CC0", "https://example.test/proof")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void retire_keepsObject_andIsNotPicked() {
        long id = jdbcTemplate.queryForObject(
                "insert into music_track (tenant_id, title, artist, object_key, duration_s, "
                        + "moods, license_type, license_source, license_proof, status, sha256, "
                        + "created_by) values (?, 'Old Hit', null, 't1/music/42.mp3', 120, "
                        + "'{upbeat}', 'CC0', 'Pixabay', 'https://example.test/proof', 'ACTIVE', "
                        + "'abc123', ?) returning id",
                Long.class, tenantId, owner.userId());

        service.retire(owner, id);

        assertThat(jdbcTemplate.queryForObject(
                "select status from music_track where id = ?", String.class, id))
                .isEqualTo("RETIRED");
        assertThat(jdbcTemplate.queryForObject(
                "select object_key from music_track where id = ?", String.class, id))
                .isEqualTo("t1/music/42.mp3");
        assertThat(service.list(tenantId).get(0).status()).isEqualTo("RETIRED");
        assertThat(picker.pick(tenantId, com.kiano.content.video.VideoType.DEMO, 0)).isEmpty();
    }

    @Test
    void list_countsVideoAdUsage() {
        long id = jdbcTemplate.queryForObject(
                "insert into music_track (tenant_id, title, artist, object_key, duration_s, "
                        + "moods, license_type, license_source, license_proof, status, sha256, "
                        + "created_by) values (?, 'Used Track', null, 't1/music/7.mp3', 90, "
                        + "'{upbeat}', 'OWNED', 'Own recording', 'https://example.test/proof', "
                        + "'ACTIVE', 'used1', ?) returning id",
                Long.class, tenantId, owner.userId());
        jdbcTemplate.update(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "status, precheck_json, provenance_json, created_at) values (?, ?, "
                        + "'VIDEO_AD', 'demo', 1, 'VIDEO', 'IN_REVIEW', '{}', ?, now())",
                tenantId, productId, "{\"musicTrackId\": " + id + "}");

        assertThat(service.list(tenantId).get(0).usedCount()).isEqualTo(1);
    }
}
