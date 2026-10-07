package com.kiano.content.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kiano.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OWNER-only workflow endpoints: multipart registration, listing and
 * activation; OPERATOR is rejected with 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WorkflowControllerTest {

    private static final String OWNER_EMAIL = "workflow-owner@example.test";
    private static final String OPERATOR_EMAIL = "workflow-operator@example.test";
    private static final String PASSWORD = "workflow-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private long tenantId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from comfy_workflow");
        jdbcTemplate.update("delete from audit_log where target_type = 'comfy_workflow'");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'", Long.class);
        jdbcTemplate.update("delete from app_user");
        jdbcTemplate.update(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, ?, 'Owner', ?, 'OWNER')",
                tenantId, OWNER_EMAIL, passwordEncoder.encode(PASSWORD));
        jdbcTemplate.update(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, ?, 'Operator', ?, 'OPERATOR')",
                tenantId, OPERATOR_EMAIL, passwordEncoder.encode(PASSWORD));
    }

    @Test
    void register_viaMultipart_returns201() throws Exception {
        mockMvc.perform(multipart("/api/v1/content/workflows")
                        .file(file("workflow", "workflow.json", "/comfy-fixtures/good/workflow.json"))
                        .file(file("manifest", "manifest.json", "/comfy-fixtures/good/manifest.json"))
                        .cookie(tokenCookie(OWNER_EMAIL)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.code").value("SCENE"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void list_showsRegisteredWorkflows() throws Exception {
        registerGood();

        mockMvc.perform(get("/api/v1/content/workflows").cookie(tokenCookie(OWNER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("SCENE"))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].status").value("DRAFT"));
    }

    @Test
    void activate_returns200() throws Exception {
        long id = registerGood();

        mockMvc.perform(post("/api/v1/content/workflows/" + id + "/activate").cookie(tokenCookie(OWNER_EMAIL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void operator_cannotRegister_403() throws Exception {
        mockMvc.perform(multipart("/api/v1/content/workflows")
                        .file(file("workflow", "workflow.json", "/comfy-fixtures/good/workflow.json"))
                        .file(file("manifest", "manifest.json", "/comfy-fixtures/good/manifest.json"))
                        .cookie(tokenCookie(OPERATOR_EMAIL)))
                .andExpect(status().isForbidden());
    }

    private long registerGood() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/v1/content/workflows")
                        .file(file("workflow", "workflow.json", "/comfy-fixtures/good/workflow.json"))
                        .file(file("manifest", "manifest.json", "/comfy-fixtures/good/manifest.json"))
                        .cookie(tokenCookie(OWNER_EMAIL)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("id").asLong();
    }

    private static MockMultipartFile file(String name, String filename, String resourcePath) throws IOException {
        try (InputStream in = WorkflowControllerTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("fixture not found: " + resourcePath);
            }
            return new MockMultipartFile(name, filename, "application/json",
                    in.readAllBytes());
        }
    }

    private Cookie tokenCookie(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotBlank();
        return new Cookie("kiano_token",
                setCookie.substring("kiano_token=".length(), setCookie.indexOf(';')));
    }
}
