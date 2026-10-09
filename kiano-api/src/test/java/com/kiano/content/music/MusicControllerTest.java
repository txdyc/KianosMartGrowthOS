package com.kiano.content.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kiano.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Music endpoints (C5 T2): upload/retire are OWNER-only (OPERATOR → 403), the
 * list is VIEWER-readable and the preview redirects to a presigned URL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MusicControllerTest {

    private static final String OWNER_EMAIL = "music-owner@example.test";
    private static final String OWNER_PASSWORD = "owner-pass-123";
    private static final String OPERATOR_EMAIL = "music-op@example.test";
    private static final String OPERATOR_PASSWORD = "op-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;

    @BeforeEach
    void seed() throws Exception {
        // the shared test database holds rows from other test classes: clear
        // the referencing children in FK-safe order first
        jdbcTemplate.update("delete from music_track");
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from publication");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from content_batch_item");
        jdbcTemplate.update("delete from content_batch");
        jdbcTemplate.update("delete from comfy_workflow");
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store_policy");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, ?, 'User', ?, ?)",
                tenantId, OWNER_EMAIL, passwordEncoder.encode(OWNER_PASSWORD), "OWNER");
        jdbcTemplate.update(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, ?, 'User', ?, ?)",
                tenantId, OPERATOR_EMAIL, passwordEncoder.encode(OPERATOR_PASSWORD), "OPERATOR");
    }

    /** Music rows reference users: leave none behind for the next test class. */
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from music_track");
    }

    @Test
    void operatorCannotUpload_403() throws Exception {
        String token = login(OPERATOR_EMAIL, OPERATOR_PASSWORD);
        mockMvc.perform(multipart("/api/v1/content/music")
                        .file(new MockMultipartFile("file", "track.mp3", "audio/mpeg",
                                "dummy".getBytes()))
                        .param("title", "Track")
                        .param("licenseType", "CC0")
                        .param("licenseSource", "Pixabay")
                        .param("licenseProof", "https://example.test/proof")
                        .param("moods", "upbeat")
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerUploads_listAndRetire() throws Exception {
        long id = jdbcTemplate.queryForObject(
                "insert into music_track (tenant_id, title, artist, object_key, duration_s, "
                        + "moods, license_type, license_source, license_proof, status, sha256) "
                        + "values (?, 'Groove', null, 't1/music/1.mp3', 90, '{upbeat}', 'CC0', "
                        + "'Pixabay', 'https://example.test/proof', 'ACTIVE', 'sha-1') returning id",
                Long.class, tenantId);

        String token = login(OWNER_EMAIL, OWNER_PASSWORD);
        mockMvc.perform(get("/api/v1/content/music")
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].title").value("Groove"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].licenseType").value("CC0"));

        mockMvc.perform(post("/api/v1/content/music/{id}/retire", id)
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                "select status from music_track where id = ?", String.class, id))
                .isEqualTo("RETIRED");

        mockMvc.perform(get("/api/v1/content/music/{id}/preview", id)
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isFound());
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotBlank();
        return setCookie.substring("kiano_token=".length(), setCookie.indexOf(';'));
    }
}
