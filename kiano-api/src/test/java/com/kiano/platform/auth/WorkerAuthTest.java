package com.kiano.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kiano.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.Map;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Worker token authentication: the /api/v1/worker/** chain accepts only the
 * worker bearer token; user JWTs get 403 there and worker tokens get 401 on
 * user endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WorkerAuthTest {

    /** sha256("test-worker-token") is fixed in application-test.yml. */
    private static final String WORKER_TOKEN = "test-worker-token";

    private static final String OWNER_EMAIL = "owner@example.test";
    private static final String OWNER_PASSWORD = "owner-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seedUsers() {
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from comfy_workflow");
        jdbcTemplate.update("delete from app_user");
        Long tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'", Long.class);
        jdbcTemplate.update(
                "insert into app_user (tenant_id, email, name, password_hash, role) values (?, ?, ?, ?, 'OWNER')",
                tenantId, OWNER_EMAIL, "Owner", passwordEncoder.encode(OWNER_PASSWORD));
    }

    private String ownerToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + OWNER_EMAIL + "\",\"password\":\"" + OWNER_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotBlank();
        return setCookie.substring("kiano_token=".length(), setCookie.indexOf(';'));
    }

    @Test
    void workerToken_onWorkerEndpoint_200() throws Exception {
        mockMvc.perform(get("/api/v1/worker/_ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pong").value("worker"));
    }

    @Test
    void missingToken_onWorkerEndpoint_401() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/worker/_ping"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo("UNAUTHENTICATED");
        assertThat(body.get("traceId")).asString().isNotBlank();
    }

    @Test
    void wrongToken_onWorkerEndpoint_401() throws Exception {
        mockMvc.perform(get("/api/v1/worker/_ping").header(HttpHeaders.AUTHORIZATION, "Bearer not-the-worker-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void userJwt_onWorkerEndpoint_403() throws Exception {
        String token = ownerToken();
        mockMvc.perform(get("/api/v1/worker/_ping").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void workerToken_onUserEndpoint_401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
