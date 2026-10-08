package com.kiano.content.policy;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Policy endpoints: GET for VIEWER, PUT only for OWNER (OPERATOR → 403).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PolicyControllerTest {

    private static final String OWNER_EMAIL = "policy-cowner@example.test";
    private static final String OWNER_PASSWORD = "owner-pass-123";
    private static final String OP_EMAIL = "policy-cop@example.test";
    private static final String OP_PASSWORD = "op-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from store_policy");
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        for (String email : List.of(OWNER_EMAIL, OP_EMAIL)) {
            jdbcTemplate.update(
                    "insert into app_user (tenant_id, email, name, password_hash, role) "
                            + "values (?, ?, 'User', ?, ?)",
                    tenantId, email, passwordEncoder.encode(
                            email.equals(OWNER_EMAIL) ? OWNER_PASSWORD : OP_PASSWORD),
                    email.equals(OWNER_EMAIL) ? "OWNER" : "OPERATOR");
        }
    }

    @Test
    void get_policy_viewer200_notConfiguredFirst() throws Exception {
        String token = login(OWNER_EMAIL, OWNER_PASSWORD);
        mockMvc.perform(get("/api/v1/content/policy").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void owner_savesConsulted() throws Exception {
        String token = login(OWNER_EMAIL, OWNER_PASSWORD);
        String body = """
                {"DELIVERY":{"title":"Delivery","body":"Accra & Tema\\n1-3 days | <b>x</b>"},"COD":\
                {"title":"Cash on Delivery","body":"Pay on receipt"},"MOMO":{"title":"Mobile Money",\
                "body":"MTN MoMo accepted"},"WARRANTY":{"title":"Warranty","body":"1 year"},\
                "RETURNS":{"title":"Returns","body":"14 days"}}""";
        mockMvc.perform(put("/api/v1/content/policy").cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.complete").value(true));
        mockMvc.perform(get("/api/v1/content/policy").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.policy.version").value(1));
    }

    @Test
    void operator_cannotSave_403() throws Exception {
        String token = login(OP_EMAIL, OP_PASSWORD);
        mockMvc.perform(put("/api/v1/content/policy").cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"DELIVERY\":{\"title\":\"D\",\"body\":\"B\"}}"))
                .andExpect(status().isForbidden());
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotBlank();
        return setCookie.substring("kiano_token=".length(), setCookie.indexOf(';'));
    }
}