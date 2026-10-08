package com.kiano.content.pipeline;

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
 * Pipeline endpoints: start (OPERATOR, 202), progress (VIEWER, 200/204),
 * job retry (OPERATOR) and the worker list with online flags (VIEWER).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PipelineControllerTest {

    private static final String OPERATOR_EMAIL = "pipe-op@example.test";
    private static final String OPERATOR_PASSWORD = "op-pass-123";
    private static final String VIEWER_EMAIL = "pipe-viewer@example.test";
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
    private long runId;

    @BeforeEach
    void seed() throws Exception {
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from worker_status");
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
        for (String code : List.of("CUTOUT", "SCENE")) {
            jdbcTemplate.update(
                    "insert into comfy_workflow (tenant_id, code, version, workflow_json, manifest_json, "
                            + "model_refs, status) values (?, ?, 1, '{}', ?, '[]', 'APPROVED')",
                    tenantId, code, "{\"code\":\"" + code + "\"}");
        }
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, -1000, 'simple', 'MG-BL200', 'Kettle 1.7L', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
        runId = jdbcTemplate.queryForObject(
                "insert into generation_run (tenant_id, product_id, kind, status) "
                        + "values (?, ?, 'IMAGE_SET', 'RUNNING') returning id",
                Long.class, tenantId, productId);
        jdbcTemplate.update(
                "insert into generation_job (tenant_id, product_id, run_id, step, asset_spec_code, variant, "
                        + "executor, input_json, parent_job_ids, status, attempts, max_attempts) "
                        + "values (?, ?, ?, 'CUTOUT', null, 'P1', 'COMFYUI', '{}', '{}', 'FAILED', 3, 3)",
                tenantId, productId, runId);
        // A worker seen just now and one seen minutes ago.
        jdbcTemplate.update(
                "insert into worker_status (worker_id, last_seen_at, capabilities, unavailable) "
                        + "values ('w1', now(), '[\"COMFYUI\"]', '[]')");
        jdbcTemplate.update(
                "insert into worker_status (worker_id, last_seen_at, capabilities, unavailable) "
                        + "values ('w2', now() - interval '10 minutes', '[\"COMPOSITE\"]', '[]')");
    }

    @Test
    void startPipeline_operator202_runId() throws Exception {
        String token = login(OPERATOR_EMAIL, OPERATOR_PASSWORD);
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        for (String code : List.of("P1", "P2", "P3", "P4", "P6", "P8")) {
            insertMedia(code);
        }

        MvcResult result = mockMvc.perform(post("/api/v1/content/products/{id}/image-pipeline",
                        productId).cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").isNumber())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("error");
    }

    @Test
    void startPipeline_viewer_403() throws Exception {
        String token = login(VIEWER_EMAIL, VIEWER_PASSWORD);
        for (String code : List.of("P1", "P2", "P3", "P4", "P6", "P8")) {
            insertMedia(code);
        }
        mockMvc.perform(post("/api/v1/content/products/{id}/image-pipeline", productId)
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void progress_neverRun_204_then200AfterStart() throws Exception {
        String token = login(VIEWER_EMAIL, VIEWER_PASSWORD);
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");

        mockMvc.perform(get("/api/v1/content/products/{id}/image-pipeline", productId)
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isNoContent());

        long newRunId = jdbcTemplate.queryForObject(
                "insert into generation_run (tenant_id, product_id, kind, status) "
                        + "values (?, ?, 'IMAGE_SET', 'RUNNING') returning id",
                Long.class, tenantId, productId);
        jdbcTemplate.update(
                "insert into generation_job (tenant_id, product_id, run_id, step, asset_spec_code, variant, "
                        + "executor, input_json, parent_job_ids, status, attempts, max_attempts) "
                        + "values (?, ?, ?, 'CUTOUT', null, 'P1', 'COMFYUI', '{}', '{}', 'FAILED', 3, 3)",
                tenantId, productId, newRunId);
        mockMvc.perform(get("/api/v1/content/products/{id}/image-pipeline", productId)
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").isNumber())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.jobs.length()").value(1))
                .andExpect(jsonPath("$.jobs[0].step").value("CUTOUT"))
                .andExpect(jsonPath("$.jobs[0].variant").value("P1"))
                .andExpect(jsonPath("$.jobs[0].executor").value("COMFYUI"))
                .andExpect(jsonPath("$.jobs[0].status").value("FAILED"))
                .andExpect(jsonPath("$.jobs[0].attempts").value(3));
    }

    @Test
    void retry_failedJob_operator() throws Exception {
        String token = login(OPERATOR_EMAIL, OPERATOR_PASSWORD);
        Long jobId = jdbcTemplate.queryForObject(
                "select id from generation_job where run_id = ?", Long.class, runId);

        mockMvc.perform(post("/api/v1/content/jobs/{id}/retry", jobId)
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk());

        String status = jdbcTemplate.queryForObject(
                "select status from generation_job where id = ?", String.class, jobId);
        assertThat(status).isEqualTo("QUEUED");
    }

    @Test
    void workers_viewer_listWithOnlineFlag() throws Exception {
        String token = login(VIEWER_EMAIL, VIEWER_PASSWORD);
        mockMvc.perform(get("/api/v1/content/workers").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].workerId").value("w1"))
                .andExpect(jsonPath("$[0].online").value(true))
                .andExpect(jsonPath("$[0].capabilities[0]").value("COMFYUI"))
                .andExpect(jsonPath("$[1].workerId").value("w2"))
                .andExpect(jsonPath("$[1].online").value(false));
    }

    private void insertMedia(String shotCode) {
        jdbcTemplate.update(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, thumb_object_key, content_type, size_bytes, sha256, qc_json, status) "
                        + "values (?, ?, ?, 'PHOTO', ?, ?, null, 'image/jpeg', 1024, ?, '{}', 'ACCEPTED')",
                tenantId, productId, shotCode, "MG-X_" + shotCode + ".jpg",
                "t" + tenantId + "/source-media/" + productId + "/" + shotCode + ".png",
                shotCode.hashCode() + "0".repeat(58));
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
