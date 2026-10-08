package com.kiano.commerce.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kiano.TestcontainersConfiguration;
import com.kiano.commerce.woo.WooCredentials;
import com.kiano.platform.integration.IntegrationStore;
import com.kiano.platform.queue.TaskQueue;
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

/**
 * Sync trigger and latest-status endpoints: dedupe of queued syncs, 409 when
 * the integration is missing, OPERATOR gate, and TaskView payload.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SyncControllerTest {

    private static final String OPERATOR_EMAIL = "operator@example.test";
    private static final String OPERATOR_PASSWORD = "operator-pass-123";
    private static final String VIEWER_EMAIL = "viewer@example.test";
    private static final String VIEWER_PASSWORD = "viewer-pass-123";
    private static final String PATH = "/api/v1/commerce/sync";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private IntegrationStore integrationStore;

    @Autowired
    private TaskQueue taskQueue;

    private long tenantId;

    @BeforeEach
    void clean() {
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update("delete from platform_task");
        jdbcTemplate.update("delete from integration");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from comfy_workflow");
        jdbcTemplate.update("delete from store_policy");
        jdbcTemplate.update("delete from app_user");
        insertUser(OPERATOR_EMAIL, OPERATOR_PASSWORD, "OPERATOR");
        insertUser(VIEWER_EMAIL, VIEWER_PASSWORD, "VIEWER");
    }

    private void insertUser(String email, String password, String role) {
        jdbcTemplate.update(
                "insert into app_user (tenant_id, email, name, password_hash, role) values (?, ?, ?, ?, ?)",
                tenantId, email, "User " + role, passwordEncoder.encode(password), role);
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

    private void configureIntegration() {
        integrationStore.save(tenantId, "WOOCOMMERCE", "ck_user",
                new WooCredentials("https://woo.example.test", "ck_user", "app-pass"));
    }

    @Test
    void postSync_twice_secondAlreadyQueued() throws Exception {
        String token = login(OPERATOR_EMAIL, OPERATOR_PASSWORD);
        configureIntegration();

        mockMvc.perform(post(PATH).cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").exists());

        mockMvc.perform(post(PATH).cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.alreadyQueued").value(true));
    }

    @Test
    void postSync_notConfigured_returns409() throws Exception {
        String token = login(OPERATOR_EMAIL, OPERATOR_PASSWORD);

        mockMvc.perform(post(PATH).cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WOO_NOT_CONFIGURED"));
    }

    @Test
    void viewer_postSync_returns403() throws Exception {
        String token = login(VIEWER_EMAIL, VIEWER_PASSWORD);
        configureIntegration();

        mockMvc.perform(post(PATH).cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void latest_returnsTaskView() throws Exception {
        String token = login(OPERATOR_EMAIL, OPERATOR_PASSWORD);
        Long taskId = taskQueue.enqueue(tenantId, "WOO_PRODUCT_SYNC", Map.of(), "woo-product-sync")
                .orElseThrow();

        mockMvc.perform(get(PATH + "/latest").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId))
                .andExpect(jsonPath("$.type").value("WOO_PRODUCT_SYNC"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }
}
