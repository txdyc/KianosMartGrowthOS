package com.kiano.commerce.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.BasicCredentials;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.kiano.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * OWNER-only WooCommerce integration settings API: encrypted credential
 * storage, store row upsert, audit without secrets, and connection test
 * against a WireMock "Woo" site.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WooIntegrationControllerTest {

    private static final String OWNER_EMAIL = "owner@example.test";
    private static final String OWNER_PASSWORD = "owner-pass-123";
    private static final String OPERATOR_EMAIL = "operator@example.test";
    private static final String OPERATOR_PASSWORD = "operator-pass-123";
    private static final String PATH = "/api/v1/integrations/woocommerce";

    private static WireMockServer woo;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;

    @BeforeAll
    static void startWireMock() {
        woo = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        woo.start();
    }

    @AfterAll
    static void stopWireMock() {
        woo.stop();
    }

    @BeforeEach
    void clean() {
        woo.resetAll();
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'", Long.class);
        jdbcTemplate.update("delete from integration");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from app_user");
        insertUser(OWNER_EMAIL, OWNER_PASSWORD, "OWNER");
        insertUser(OPERATOR_EMAIL, OPERATOR_PASSWORD, "OPERATOR");
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

    private ResultActions putIntegration(String token, String baseUrl, String username,
            String applicationPassword) throws Exception {
        return mockMvc.perform(put(PATH)
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"" + baseUrl + "\",\"username\":\"" + username
                                + "\",\"applicationPassword\":\"" + applicationPassword + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true));
    }

    private void stubPingOk() {
        woo.stubFor(WireMock.get(urlPathEqualTo("/wp-json/wc/v3/products"))
                .withQueryParam("per_page", equalTo("1"))
                .willReturn(okJson("[]")));
    }

    @Test
    void owner_put_thenGet_neverReturnsPassword() throws Exception {
        String token = login(OWNER_EMAIL, OWNER_PASSWORD);
        MvcResult putResult = putIntegration(token, "https://shop.example.com", "ck_user",
                        "super-secret-app-pass")
                .andExpect(jsonPath("$.baseUrl").value("https://shop.example.com"))
                .andExpect(jsonPath("$.username").value("ck_user"))
                .andReturn();
        assertThat(putResult.getResponse().getContentAsString())
                .doesNotContain("applicationPassword")
                .doesNotContain("super-secret-app-pass");

        MvcResult getResult = mockMvc.perform(get(PATH).cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.baseUrl").value("https://shop.example.com"))
                .andExpect(jsonPath("$.username").value("ck_user"))
                .andReturn();
        assertThat(getResult.getResponse().getContentAsString())
                .doesNotContain("applicationPassword")
                .doesNotContain("super-secret-app-pass");
    }

    @Test
    void get_whenNotConfigured_returnsFalse() throws Exception {
        String token = login(OWNER_EMAIL, OWNER_PASSWORD);
        mockMvc.perform(get(PATH).cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void put_upsertsStoreRow() throws Exception {
        String token = login(OWNER_EMAIL, OWNER_PASSWORD);
        putIntegration(token, "https://a.example.com", "ck_user", "pass-one");
        putIntegration(token, "https://b.example.com", "ck_user", "pass-two");

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from store where tenant_id = ? and platform = 'WOOCOMMERCE'",
                Integer.class, tenantId);
        assertThat(count).isEqualTo(1);
        String baseUrl = jdbcTemplate.queryForObject(
                "select base_url from store where tenant_id = ? and platform = 'WOOCOMMERCE'",
                String.class, tenantId);
        assertThat(baseUrl).isEqualTo("https://b.example.com");
    }

    @Test
    void put_blankPassword_keepsExisting() throws Exception {
        String token = login(OWNER_EMAIL, OWNER_PASSWORD);
        putIntegration(token, woo.baseUrl(), "ck_user", "first-secret");
        putIntegration(token, woo.baseUrl(), "ck_user", "");
        stubPingOk();

        mockMvc.perform(post(PATH + "/test").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        woo.verify(getRequestedFor(urlPathEqualTo("/wp-json/wc/v3/products"))
                .withBasicAuth(new BasicCredentials("ck_user", "first-secret")));
    }

    @Test
    void operator_put_returns403() throws Exception {
        String token = login(OPERATOR_EMAIL, OPERATOR_PASSWORD);
        mockMvc.perform(put(PATH)
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"https://x\",\"username\":\"u\",\"applicationPassword\":\"p\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from integration", Integer.class))
                .isZero();
    }

    @Test
    void test_ok_againstWireMock() throws Exception {
        String token = login(OWNER_EMAIL, OWNER_PASSWORD);
        putIntegration(token, woo.baseUrl(), "ck_user", "app-pass");
        stubPingOk();

        mockMvc.perform(post(PATH + "/test").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void test_reportsAuthFailure() throws Exception {
        String token = login(OWNER_EMAIL, OWNER_PASSWORD);
        putIntegration(token, woo.baseUrl(), "ck_user", "app-pass");
        woo.stubFor(WireMock.get(urlPathEqualTo("/wp-json/wc/v3/products"))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"woocommerce_rest_cannot_view\",\"data\":{\"status\":401}}")));

        mockMvc.perform(post(PATH + "/test").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("WOO_AUTH_FAILED"))
                .andExpect(jsonPath("$.message").value(
                        "WooCommerce rejected the credentials. Check the username and Application Password; "
                                + "on an http:// site WordPress also needs WP_ENVIRONMENT_TYPE=local."));
    }

    @Test
    void audit_hasNoSecret() throws Exception {
        String token = login(OWNER_EMAIL, OWNER_PASSWORD);
        putIntegration(token, "https://shop.example.com", "ck_user", "super-secret-app-pass");

        String action = jdbcTemplate.queryForObject(
                "select action from audit_log order by id desc limit 1", String.class);
        String targetType = jdbcTemplate.queryForObject(
                "select target_type from audit_log order by id desc limit 1", String.class);
        assertThat(action).isEqualTo("INTEGRATION_UPDATED");
        assertThat(targetType).isEqualTo("integration");

        java.util.List<String> rows = jdbcTemplate.queryForList(
                "select coalesce(actor_id, '') || '|' || action || '|' || coalesce(target_type, '') || '|' "
                        + "|| coalesce(target_id, '') || '|' || coalesce(before_json::text, '') || '|' "
                        + "|| coalesce(after_json::text, '') || '|' || coalesce(reason, '') || '|' "
                        + "|| coalesce(source, '') from audit_log where tenant_id = ?",
                String.class, tenantId);
        assertThat(rows).isNotEmpty();
        rows.forEach(row -> assertThat(row).doesNotContain("super-secret-app-pass"));
    }
}
