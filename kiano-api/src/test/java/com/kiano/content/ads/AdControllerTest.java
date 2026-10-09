package com.kiano.content.ads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kiano.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
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
 * Ad render endpoints: the price-only mode is internal (the client cannot use
 * it to skip the preconditions), and the latest render outcome per product is
 * readable so partial renders are not silent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AdControllerTest {

    private static final String OP_EMAIL = "ads-ctl-op@example.test";
    private static final String OP_PASSWORD = "op-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;
    private long productId;
    private long otherProductId;

    @BeforeEach
    void seed() {
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update("delete from platform_task where tenant_id = ?", tenantId);
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        long storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = product(storeId, -6101, "MG-KTL17");
        otherProductId = product(storeId, -6102, "MG-BL200");
        jdbcTemplate.update("insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, ?, 'Op', ?, 'OPERATOR')",
                tenantId, OP_EMAIL, passwordEncoder.encode(OP_PASSWORD));
    }

    @Test
    void render_clientPriceOnlyFlag_ignored_preconditionsStillEnforced() throws Exception {
        // a STANDARD product without copy, bases or badges
        mockMvc.perform(post("/api/v1/content/products/" + productId + "/ads/render")
                        .cookie(new Cookie("kiano_token", login()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priceOnly\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AD_PRECONDITIONS"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from platform_task where tenant_id = ? and type = 'AD_RENDER'",
                Integer.class, tenantId)).isZero();
    }

    @Test
    void renderStatus_returnsLatestOutcomeForThatProduct() throws Exception {
        task(productId, "SUCCEEDED",
                "{\"rendered\":[\"pricehook-1080x1080\"],"
                        + "\"skipped\":{\"demo-1080x1080\":\"DEMO_FRAME_UNAVAILABLE\"}}", null);
        // a newer task of another product must not shadow this product's outcome
        task(otherProductId, "FAILED", null, "AD_PRECONDITIONS: x");

        mockMvc.perform(get("/api/v1/content/products/" + productId + "/ads/render-status")
                        .cookie(new Cookie("kiano_token", login())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.rendered[0]").value("pricehook-1080x1080"))
                .andExpect(jsonPath("$.skipped['demo-1080x1080']").value("DEMO_FRAME_UNAVAILABLE"));
    }

    @Test
    void renderStatus_failedTaskCarriesError_noTaskIs204() throws Exception {
        mockMvc.perform(get("/api/v1/content/products/" + productId + "/ads/render-status")
                        .cookie(new Cookie("kiano_token", login())))
                .andExpect(status().isNoContent());

        task(productId, "FAILED", null, "TEMPLATE_NOT_FOUND: No approved template for AD_DEMO");

        mockMvc.perform(get("/api/v1/content/products/" + productId + "/ads/render-status")
                        .cookie(new Cookie("kiano_token", login())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.lastError").value(
                        "TEMPLATE_NOT_FOUND: No approved template for AD_DEMO"));
    }

    private long product(long storeId, long externalId, String sku) {
        return jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, ?, 'simple', ?, 'Product', 'publish', now()) returning id",
                Long.class, tenantId, storeId, externalId, sku);
    }

    private void task(long forProduct, String status, String resultJson, String lastError) {
        jdbcTemplate.update("insert into platform_task (tenant_id, type, payload, status, result, "
                        + "last_error, finished_at) values (?, 'AD_RENDER', ?::jsonb, ?, ?::jsonb, ?, now())",
                tenantId, "{\"productId\":" + forProduct + "}", status, resultJson, lastError);
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + OP_EMAIL + "\",\"password\":\"" + OP_PASSWORD
                                + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        return setCookie.substring("kiano_token=".length(), setCookie.indexOf(';'));
    }
}
