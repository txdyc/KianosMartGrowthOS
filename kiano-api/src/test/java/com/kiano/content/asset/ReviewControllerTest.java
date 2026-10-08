package com.kiano.content.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Review endpoints: asset list (VIEWER), single-asset decisions (OPERATOR,
 * 200/202) and approve-remaining (OPERATOR, 200).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReviewControllerTest {

    private static final String OPERATOR_EMAIL = "review-op@example.test";
    private static final String OPERATOR_PASSWORD = "op-pass-123";
    private static final String VIEWER_EMAIL = "review-viewer@example.test";
    private static final String VIEWER_PASSWORD = "viewer-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;
    private long storeId;
    private long productId;
    private long assetId;
    private long mediaId;

    @BeforeEach
    void seed() throws Exception {
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from comfy_workflow");
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from store_policy");
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        for (String email : List.of(OPERATOR_EMAIL, VIEWER_EMAIL)) {
            jdbcTemplate.update(
                    "insert into app_user (tenant_id, email, name, password_hash, role) "
                            + "values (?, ?, 'User', ?, ?)",
                    tenantId, email, passwordEncoder.encode(
                            email.equals(OPERATOR_EMAIL) ? OPERATOR_PASSWORD : VIEWER_PASSWORD),
                    email.equals(OPERATOR_EMAIL) ? "OPERATOR" : "VIEWER");
        }
        jdbcTemplate.update(
                "insert into comfy_workflow (tenant_id, code, version, workflow_json, manifest_json, "
                        + "model_refs, status) values (?, 'CUTOUT', 1, '{}', '{\"code\":\"CUTOUT\"}', "
                        + "'[]', 'APPROVED')",
                tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, -2000, 'simple', 'MG-BL200', 'Kettle 1.7L', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
        mediaId = jdbcTemplate.queryForObject(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, thumb_object_key, content_type, size_bytes, sha256, qc_json, status) "
                        + "values (?, ?, 'P2', 'PHOTO', 'MG-BL200_P2.jpg', ?, null, 'image/jpeg', 1024, "
                        + "'x0123', '{}', 'ACCEPTED') returning id",
                Long.class, tenantId, productId,
                "t" + tenantId + "/source-media/" + productId + "/P2.png");
        assetId = insertAsset("PAGE_ANGLE", "P2", 1, "IN_REVIEW");
    }

    @Test
    void list_assets_viewer200() throws Exception {
        String token = login(VIEWER_EMAIL, VIEWER_PASSWORD);
        mockMvc.perform(get("/api/v1/content/assets")
                        .param("productId", String.valueOf(productId))
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].assetId").value(assetId))
                .andExpect(jsonPath("$[0].sku").value("MG-BL200"))
                .andExpect(jsonPath("$[0].productName").value("Kettle 1.7L"))
                .andExpect(jsonPath("$[0].specCode").value("PAGE_ANGLE"))
                .andExpect(jsonPath("$[0].variant").value("P2"))
                .andExpect(jsonPath("$[0].status").value("IN_REVIEW"))
                .andExpect(jsonPath("$[0].imageUrl").exists())
                .andExpect(jsonPath("$[0].thumbUrl").exists())
                .andExpect(jsonPath("$[0].sourceThumbUrl").exists());
    }

    @Test
    void approve_viaApi_200() throws Exception {
        String token = login(OPERATOR_EMAIL, OPERATOR_PASSWORD);
        mockMvc.perform(post("/api/v1/content/assets/{id}/review", assetId)
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reasonCodes\":[],\"comment\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void reject_viaApi_requiresReasons() throws Exception {
        String token = login(OPERATOR_EMAIL, OPERATOR_PASSWORD);
        mockMvc.perform(post("/api/v1/content/assets/{id}/review", assetId)
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"reasonCodes\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void regenerate_viaApi_202_runId() throws Exception {
        String token = login(OPERATOR_EMAIL, OPERATOR_PASSWORD);
        mockMvc.perform(post("/api/v1/content/assets/{id}/review", assetId)
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REGENERATE\",\"reasonCodes\":[],\"comment\":\"redo\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").isNumber());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from generation_job where step = 'CUTOUT'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void approveRemaining_viaApi_200() throws Exception {
        String token = login(OPERATOR_EMAIL, OPERATOR_PASSWORD);
        insertAsset("PAGE_MAIN", "main", 1, "IN_REVIEW");
        insertAsset("PAGE_INBOX", "P8", 1, "IN_REVIEW");
        mockMvc.perform(post("/api/v1/content/products/{id}/review/approve-remaining", productId)
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(3));
    }

    @Test
    void viewer_cannotDecide_403() throws Exception {
        String token = login(VIEWER_EMAIL, VIEWER_PASSWORD);
        mockMvc.perform(post("/api/v1/content/assets/{id}/review", assetId)
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reasonCodes\":[]}"))
                .andExpect(status().isForbidden());
    }

    private long insertAsset(String specCode, String variant, int version, String status) {
        String prefix = "t" + tenantId + "/assets/" + productId + "/" + specCode + "/" + variant
                + "/v" + version;
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "object_key, thumb_object_key, width, height, status, precheck_json, "
                        + "provenance_json, file_name) values (?, ?, ?, ?, ?, 'IMAGE', ?, ?, 1600, "
                        + "1600, ?, '{\"flags\":[],\"metrics\":{}}'::jsonb, "
                        + "'{\"sourceMediaIds\":[" + mediaId + "]}'::jsonb, 'MG-BL200_x.jpg') returning id",
                Long.class, tenantId, productId, specCode, variant, version,
                prefix + ".jpg", prefix + "_thumb.jpg", status);
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
