package com.kiano.content.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.ContentTier;
import com.kiano.commerce.persistence.ProductEntity;
import com.kiano.commerce.persistence.ProductMapper;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * PATCH /api/v1/products/{id}/profile: tier upsert with audit, top-level
 * enforcement, validation of the tier value, and the OPERATOR gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProfileControllerTest {

    private static final String OPERATOR_EMAIL = "operator@example.test";
    private static final String OPERATOR_PASSWORD = "operator-pass-123";
    private static final String VIEWER_EMAIL = "viewer@example.test";
    private static final String VIEWER_PASSWORD = "viewer-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductProfileService profileService;

    private long tenantId;
    private long storeId;
    private long productId;
    private long variationId;

    @BeforeEach
    void setUp() {
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from comfy_workflow");
        jdbcTemplate.update("delete from store_policy");
        jdbcTemplate.update("delete from app_user");
        insertUser(OPERATOR_EMAIL, OPERATOR_PASSWORD, "OPERATOR");
        insertUser(VIEWER_EMAIL, VIEWER_PASSWORD, "VIEWER");

        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) values (?, 'WOOCOMMERCE', 'https://woo.example.test') returning id",
                Long.class, tenantId);

        productId = insertProduct(301L, null, "simple", "MG-BL200").getId();
        variationId = insertProduct(3011L, productId, "variation", "MG-BL200-BLK").getId();
    }

    private void insertUser(String email, String password, String role) {
        jdbcTemplate.update(
                "insert into app_user (tenant_id, email, name, password_hash, role) values (?, ?, ?, ?, ?)",
                tenantId, email, "User " + role, passwordEncoder.encode(password), role);
    }

    private ProductEntity insertProduct(Long externalId, Long parentId, String type, String sku) {
        ProductEntity entity = new ProductEntity();
        entity.setTenantId(tenantId);
        entity.setStoreId(storeId);
        entity.setExternalId(externalId);
        entity.setParentId(parentId);
        entity.setType(type);
        entity.setSku(sku);
        entity.setName("Product " + sku);
        entity.setStatus("publish");
        entity.setSyncedAt(OffsetDateTime.now(ZoneOffset.UTC));
        productMapper.insert(entity);
        assertThat(entity.getId()).isNotNull();
        return entity;
    }

    private String loginViaPost(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotBlank();
        return setCookie.substring("kiano_token=".length(), setCookie.indexOf(';'));
    }

    @Test
    void defaultTier_isStandard() {
        assertThat(profileService.tierOf(tenantId, productId)).isEqualTo(ContentTier.STANDARD);
    }

    @Test
    void patch_setsHero_andAudits() throws Exception {
        String token = loginViaPost(OPERATOR_EMAIL, OPERATOR_PASSWORD);

        mockMvc.perform(patch("/api/v1/products/{id}/profile", productId)
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentTier\":\"HERO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(productId))
                .andExpect(jsonPath("$.contentTier").value("HERO"));

        assertThat(profileService.tierOf(tenantId, productId)).isEqualTo(ContentTier.HERO);

        String before = jdbcTemplate.queryForObject(
                "select before_json::text from audit_log where action = 'PRODUCT_PROFILE_UPDATED'",
                String.class);
        String after = jdbcTemplate.queryForObject(
                "select after_json::text from audit_log where action = 'PRODUCT_PROFILE_UPDATED'",
                String.class);
        assertThat(before).contains("STANDARD");
        assertThat(after).contains("HERO");
    }

    @Test
    void patch_variation_returns422() throws Exception {
        String token = loginViaPost(OPERATOR_EMAIL, OPERATOR_PASSWORD);

        mockMvc.perform(patch("/api/v1/products/{id}/profile", variationId)
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentTier\":\"HERO\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NOT_TOP_LEVEL_PRODUCT"));
    }

    @Test
    void patch_unknownProduct_returns404() throws Exception {
        String token = loginViaPost(OPERATOR_EMAIL, OPERATOR_PASSWORD);

        mockMvc.perform(patch("/api/v1/products/{id}/profile", 999_999_999)
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentTier\":\"HERO\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void patch_invalidTier_returns400() throws Exception {
        String token = loginViaPost(OPERATOR_EMAIL, OPERATOR_PASSWORD);

        mockMvc.perform(patch("/api/v1/products/{id}/profile", productId)
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentTier\":\"PREMIUM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void viewer_patch_returns403() throws Exception {
        String token = loginViaPost(VIEWER_EMAIL, VIEWER_PASSWORD);

        mockMvc.perform(patch("/api/v1/products/{id}/profile", productId)
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentTier\":\"HERO\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
