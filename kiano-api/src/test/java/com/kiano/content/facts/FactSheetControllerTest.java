package com.kiano.content.facts;

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
 * Fact sheet endpoints: GET returns current/locked plus P5/PROMO source URLs
 * (VIEWER), PUT draft and POST lock are OPERATOR, lock forbidden for VIEWER.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class FactSheetControllerTest {

    private static final String OP_EMAIL = "facts-cop@example.test";
    private static final String OP_PASSWORD = "op-pass-123";
    private static final String VIEWER_EMAIL = "facts-cview@example.test";
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

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from llm_call");
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, -3001, 'simple', 'MG-KTL17', 'Kettle 1.7L', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
        for (String email : List.of(OP_EMAIL, VIEWER_EMAIL)) {
            jdbcTemplate.update(
                    "insert into app_user (tenant_id, email, name, password_hash, role) "
                            + "values (?, ?, 'User', ?, ?)",
                    tenantId, email, passwordEncoder.encode(
                            email.equals(OP_EMAIL) ? OP_PASSWORD : VIEWER_PASSWORD),
                    email.equals(OP_EMAIL) ? "OPERATOR" : "VIEWER");
        }
        jdbcTemplate.update(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, thumb_object_key, content_type, size_bytes, sha256, qc_json, status) "
                        + "values (?, ?, 'P5', 'PHOTO', 'MG-KTL17_P5.jpg', ?, null, 'image/jpeg', 1024, "
                        + "'aabb', '{}', 'ACCEPTED')",
                tenantId, productId, "t" + tenantId + "/source-media/" + productId + "/P5.jpg");
    }

    @Test
    void get_facts_viewer200_withSources() throws Exception {
        String token = login(VIEWER_EMAIL, VIEWER_PASSWORD);
        mockMvc.perform(get("/api/v1/content/products/" + productId + "/facts")
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources.P5.url").isString());
    }

    @Test
    void viewer_cannotLock_403() throws Exception {
        String token = login(VIEWER_EMAIL, VIEWER_PASSWORD);
        mockMvc.perform(post("/api/v1/content/products/" + productId + "/facts/lock")
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draftVersion\":1,\"confirmedFields\":[\"model\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void operator_savesThenLocks() throws Exception {
        String token = login(OP_EMAIL, OP_PASSWORD);
        String draftBody = """
                {"facts":{"model":"MG-KTL17","category":"Electric Kettles","capacity":"1.7 L",\
                "powerW":2000,"voltage":"220-240V","material":"Stainless steel","colour":"Silver",\
                "warranty":"1 year","inBox":["Kettle","Base"],"features":["Auto shut-off"],\
                "benefits":["Boils quickly"],"forbiddenClaims":[]},
                 "fieldSources":{"model":"P5","capacity":"P5","powerW":"P5","voltage":"P5",\
                "warranty":"WOO_TEXT","inBox":"PROMO"}}""";
        mockMvc.perform(put("/api/v1/content/products/" + productId + "/facts/draft")
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        String lockBody = """
                {"draftVersion":1,"confirmedFields":["model","capacity","powerW","voltage",\
                "warranty","inBox"]}""";
        mockMvc.perform(post("/api/v1/content/products/" + productId + "/facts/lock")
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lockBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.lockedBy").value(OP_EMAIL));

        mockMvc.perform(get("/api/v1/content/products/" + productId + "/facts")
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked.version").value(1));
    }

    @Test
    void lock_missingConfirmations_422WithMissing() throws Exception {
        String token = login(OP_EMAIL, OP_PASSWORD);
        mockMvc.perform(put("/api/v1/content/products/" + productId + "/facts/draft")
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"facts\":{\"model\":\"MG-KTL17\"},\"fieldSources\":{\"model\":\"P5\"}}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/content/products/" + productId + "/facts/lock")
                        .cookie(new Cookie("kiano_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draftVersion\":1,\"confirmedFields\":[\"model\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FACTS_NOT_CONFIRMED"));
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