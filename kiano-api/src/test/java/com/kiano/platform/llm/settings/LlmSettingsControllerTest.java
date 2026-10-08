package com.kiano.platform.llm.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * OWNER-only LLM settings API: 403 for non-OWNER on every endpoint, settings
 * and audit without keys, default-route marking and DeepSeek presets.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class LlmSettingsControllerTest {

    private static final String PATH = "/api/v1/settings/llm";
    private static final String OWNER_EMAIL = "llm-owner@example.test";
    private static final String OWNER_PASSWORD = "owner-pass-123";
    private static final String OPERATOR_EMAIL = "llm-operator@example.test";
    private static final String OPERATOR_PASSWORD = "operator-pass-123";
    private static final String TOP_SECRET = "sk-topsecret-123456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;
    private String ownerToken;
    private String operatorToken;

    @BeforeEach
    void clean() throws Exception {
        jdbc.update("delete from llm_route");
        jdbc.update("delete from llm_provider");
        jdbc.update("delete from llm_call");
        jdbc.update("delete from audit_log");
        jdbc.update("delete from app_user");
        tenantId = jdbc.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        insertUser(OWNER_EMAIL, OWNER_PASSWORD, "OWNER");
        insertUser(OPERATOR_EMAIL, OPERATOR_PASSWORD, "OPERATOR");
        ownerToken = login(OWNER_EMAIL, OWNER_PASSWORD);
        operatorToken = login(OPERATOR_EMAIL, OPERATOR_PASSWORD);
    }

    @Test
    void nonOwner_403_onAllEndpoints() throws Exception {
        mockMvc.perform(get(PATH).cookie(new Cookie("kiano_token", operatorToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH + "/providers").cookie(new Cookie("kiano_token", operatorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(providerBody()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(PATH + "/providers/1").cookie(new Cookie("kiano_token", operatorToken))
                        .contentType(MediaType.APPLICATION_JSON).content(providerBody()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(PATH + "/providers/1")
                        .cookie(new Cookie("kiano_token", operatorToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(PATH + "/routes/COPY").cookie(new Cookie("kiano_token", operatorToken))
                        .contentType(MediaType.APPLICATION_JSON).content(routeBody()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(PATH + "/routes/COPY")
                        .cookie(new Cookie("kiano_token", operatorToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PATH + "/routes/COPY/test")
                        .cookie(new Cookie("kiano_token", operatorToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getSettings_andAudit_neverContainKey() throws Exception {
        mockMvc.perform(post(PATH + "/providers").cookie(new Cookie("kiano_token", ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"DeepSeek\",\"kind\":\"OPENAI_COMPATIBLE\","
                                + "\"baseUrl\":\"https://api.deepseek.com\",\"apiKey\":\""
                                + TOP_SECRET + "\"}"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get(PATH)
                        .cookie(new Cookie("kiano_token", ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers[0].name").value("DeepSeek"))
                .andExpect(jsonPath("$.providers[0].hasKey").value(true))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(TOP_SECRET);

        List<String> audit = jdbc.queryForList("select coalesce(before_json::text, '') || ' ' "
                + "|| coalesce(after_json::text, '') from audit_log", String.class);
        assertThat(audit).isNotEmpty();
        assertThat(audit).allSatisfy(lines -> assertThat(lines).doesNotContain(TOP_SECRET));
    }

    @Test
    void routesListShowsUsingDefault() throws Exception {
        mockMvc.perform(get(PATH).cookie(new Cookie("kiano_token", ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes.length()").value(2))
                .andExpect(jsonPath("$.routes[0].purpose").value("FACT_DRAFT"))
                .andExpect(jsonPath("$.routes[0].usingDefault").value(true))
                .andExpect(jsonPath("$.routes[1].purpose").value("COPY"))
                .andExpect(jsonPath("$.routes[1].usingDefault").value(true));
    }

    @Test
    void presetsIncludeDeepSeekFlashWithVision() throws Exception {
        mockMvc.perform(get(PATH).cookie(new Cookie("kiano_token", ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presets[?(@.name=='DeepSeek')].models[0].model")
                        .value("deepseek-flash"))
                .andExpect(jsonPath("$.presets[?(@.name=='DeepSeek')].models[0].supportsImages")
                        .value(true))
                .andExpect(jsonPath("$.presets[?(@.name=='DeepSeek')].baseUrl")
                        .value("https://api.deepseek.com"));
    }

    @Test
    void deleteProvider_thenAudit_hasNoKey() throws Exception {
        ResultActions created = mockMvc.perform(post(PATH + "/providers")
                .cookie(new Cookie("kiano_token", ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Anthropic\",\"kind\":\"ANTHROPIC\",\"apiKey\":\""
                        + TOP_SECRET + "\"}"))
                .andExpect(status().isOk());
        long id = Long.parseLong(created.andReturn().getResponse()
                .getContentAsString().replaceAll("\\D", ""));

        mockMvc.perform(delete(PATH + "/providers/" + id)
                        .cookie(new Cookie("kiano_token", ownerToken)))
                .andExpect(status().isOk());
        List<String> audit = jdbc.queryForList("select coalesce(before_json::text, '') || ' ' "
                + "|| coalesce(after_json::text, '') from audit_log", String.class);
        assertThat(audit).isNotEmpty();
        assertThat(audit).allSatisfy(lines -> assertThat(lines).doesNotContain(TOP_SECRET));
    }

    // ---- helpers ----

    private void insertUser(String email, String password, String role) {
        jdbc.update("insert into app_user (tenant_id, email, name, password_hash, role) "
                + "values (?, ?, ?, ?, ?)", tenantId, email, "User " + role,
                passwordEncoder.encode(password), role);
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

    private static String providerBody() {
        return "{\"name\":\"DeepSeek\",\"kind\":\"OPENAI_COMPATIBLE\","
                + "\"baseUrl\":\"https://api.deepseek.com\",\"apiKey\":\"sk-ds-1\"}";
    }

    private static String routeBody() {
        return "{\"providerId\":1,\"model\":\"deepseek-flash\",\"supportsImages\":true,"
                + "\"inputPerMtok\":0.30,\"outputPerMtok\":1.20,\"cacheReadPerMtok\":0.006}";
    }
}