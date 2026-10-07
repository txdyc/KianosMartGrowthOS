package com.kiano.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kiano.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AuthControllerTest {

    private static final String OWNER_EMAIL = "owner@example.test";
    private static final String OWNER_PASSWORD = "owner-pass-123";
    private static final String VIEWER_EMAIL = "viewer@example.test";
    private static final String VIEWER_PASSWORD = "viewer-pass-123";

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
        jdbcTemplate.update("delete from app_user");
        Long tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'", Long.class);
        insertUser(tenantId, OWNER_EMAIL, OWNER_PASSWORD, "OWNER");
        insertUser(tenantId, VIEWER_EMAIL, VIEWER_PASSWORD, "VIEWER");
    }

    private void insertUser(Long tenantId, String email, String password, String role) {
        jdbcTemplate.update(
                "insert into app_user (tenant_id, email, name, password_hash, role) values (?, ?, ?, ?, ?)",
                tenantId, email, "User " + role, passwordEncoder.encode(password), role);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String tokenFrom(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotBlank();
        return setCookie.substring("kiano_token=".length(), setCookie.indexOf(';'));
    }

    @Test
    void login_setsHttpOnlyCookie() throws Exception {
        MvcResult result = login(OWNER_EMAIL, OWNER_PASSWORD);
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("kiano_token=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Lax");
        assertThat(setCookie).contains("Path=/");
        assertThat(setCookie).contains("Max-Age=43200");
    }

    @Test
    void login_isCaseInsensitiveOnEmail() throws Exception {
        login("Owner@Example.test", OWNER_PASSWORD);
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + OWNER_EMAIL + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo("AUTH_INVALID_CREDENTIALS");
        assertThat(body.get("traceId")).asString().isNotBlank();
        assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo(body.get("traceId"));
    }

    @Test
    void me_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void me_withCookie_returnsUser() throws Exception {
        String token = tokenFrom(login(OWNER_EMAIL, OWNER_PASSWORD));
        mockMvc.perform(get("/api/v1/auth/me").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.email").value(OWNER_EMAIL));
    }

    @Test
    void me_withBearerHeader_returnsUser() throws Exception {
        String token = tokenFrom(login(OWNER_EMAIL, OWNER_PASSWORD));
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OWNER"));
    }

    @Test
    void logout_expiresCookie() throws Exception {
        String token = tokenFrom(login(OWNER_EMAIL, OWNER_PASSWORD));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isNoContent())
                .andReturn();
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("Max-Age=0");
    }

    @Test
    void viewer_onOperatorEndpoint_returns403() throws Exception {
        String token = tokenFrom(login(VIEWER_EMAIL, VIEWER_PASSWORD));
        mockMvc.perform(post("/api/v1/_test/operator").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void owner_onOperatorEndpoint_returns200() throws Exception {
        String token = tokenFrom(login(OWNER_EMAIL, OWNER_PASSWORD));
        mockMvc.perform(post("/api/v1/_test/operator").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk());
    }

    @Test
    void corsPreflight_fromWebOrigin_allowsCredentials() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }
}
