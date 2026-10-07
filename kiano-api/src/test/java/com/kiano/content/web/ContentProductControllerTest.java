package com.kiano.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kiano.TestcontainersConfiguration;
import com.kiano.commerce.persistence.ProductEntity;
import com.kiano.commerce.persistence.ProductMapper;
import jakarta.servlet.http.Cookie;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET /api/v1/content/products (summary list with tier/q filter) and
 * GET /api/v1/content/products/{id}/shots (checklist with presigned URLs).
 * Both are open to VIEWER.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ContentProductControllerTest {

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

    @Value("${kiano.storage.public-endpoint}")
    private String publicEndpoint;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long tenantId;
    private long bl200Id;
    private long fan16Id;

    private long mediaSeq;

    @BeforeEach
    void setUp() {
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        jdbcTemplate.update(
                "insert into app_user (tenant_id, email, name, password_hash, role) values (?, ?, ?, ?, 'VIEWER')",
                tenantId, VIEWER_EMAIL, "Viewer", passwordEncoder.encode(VIEWER_PASSWORD));
        mediaSeq = 0;
        long storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) values (?, 'WOOCOMMERCE', 'https://woo.example.test') returning id",
                Long.class, tenantId);

        bl200Id = insertProduct(storeId, 301L, null, "simple", "MG-BL200", "Kettle, 1.7L");
        fan16Id = insertProduct(storeId, 302L, null, "variable", "MG-FAN16", "Standing Fan 16in");
        insertProduct(storeId, 303L, null, "simple", "MG-GONE", "Disappeared product", "missing");
        long kettleCategory = jdbcTemplate.queryForObject(
                "insert into category (tenant_id, external_id, name, slug, synced_at) "
                        + "values (?, 401, 'Kettles', 'kettles', now()) returning id",
                Long.class, tenantId);
        jdbcTemplate.update("insert into product_category (product_id, category_id) values (?, ?)",
                bl200Id, kettleCategory);
        jdbcTemplate.update("insert into product_profile (product_id, tenant_id, role_source, content_tier) "
                + "values (?, ?, 'MANUAL', 'HERO')", bl200Id, tenantId);

        insertMedia(bl200Id, "P1", "PHOTO", "ACCEPTED");
        insertMedia(bl200Id, "P3", "PHOTO", "RESHOOT", "BLURRY");
        for (String code : new String[] {"P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8"}) {
            insertMedia(fan16Id, code, "PHOTO", "ACCEPTED");
        }
    }

    @Test
    void products_endpoint_returnsSummaries() throws Exception {
        String token = loginViaPost(VIEWER_EMAIL, VIEWER_PASSWORD);

        mockMvc.perform(get("/api/v1/content/products").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sku").value("MG-BL200"))
                .andExpect(jsonPath("$[0].name").value("Kettle, 1.7L"))
                .andExpect(jsonPath("$[0].tier").value("HERO"))
                .andExpect(jsonPath("$[0].required").value(12))
                .andExpect(jsonPath("$[0].ok").value(1))
                .andExpect(jsonPath("$[0].reshoot").value(1))
                .andExpect(jsonPath("$[0].missing").value(10))
                .andExpect(jsonPath("$[0].complete").value(false))
                .andExpect(jsonPath("$[1].sku").value("MG-FAN16"))
                .andExpect(jsonPath("$[1].tier").value("STANDARD"))
                .andExpect(jsonPath("$[1].complete").value(true));

        mockMvc.perform(get("/api/v1/content/products").param("tier", "STANDARD")
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sku").value("MG-FAN16"));

        mockMvc.perform(get("/api/v1/content/products").param("q", "bl200")
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sku").value("MG-BL200"));
    }

    @Test
    void shots_endpoint_urlsUsePublicEndpoint() throws Exception {
        String token = loginViaPost(VIEWER_EMAIL, VIEWER_PASSWORD);

        MvcResult result = mockMvc.perform(get("/api/v1/content/products/" + bl200Id + "/shots")
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value((int) bl200Id))
                .andExpect(jsonPath("$.sku").value("MG-BL200"))
                .andExpect(jsonPath("$.name").value("Kettle, 1.7L"))
                .andExpect(jsonPath("$.tier").value("HERO"))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.lines.length()").value(12))
                .andReturn();

        JsonNode root = jsonMapper.readTree(result.getResponse().getContentAsString());
        JsonNode p1 = findLine(root, "P1");
        assertThat(p1.get("state").asText()).isEqualTo("OK");
        assertThat(p1.get("thumbUrl").asText()).startsWith(publicEndpoint);
        assertThat(p1.get("mediaUrl").asText()).startsWith(publicEndpoint);

        JsonNode p3 = findLine(root, "P3");
        assertThat(p3.get("state").asText()).isEqualTo("RESHOOT");
        assertThat(p3.get("reasons").get(0).asText()).isEqualTo("BLURRY");
        assertThat(p3.get("mediaUrl").asText()).startsWith(publicEndpoint);

        JsonNode v1 = findLine(root, "V1");
        assertThat(v1.get("state").asText()).isEqualTo("MISSING");
        assertThat(v1.path("mediaUrl").isMissingNode() || !v1.hasNonNull("mediaUrl")).isTrue();
        assertThat(v1.path("thumbUrl").isMissingNode() || !v1.hasNonNull("thumbUrl")).isTrue();
    }

    private long insertProduct(long storeId, long externalId, Long parentId, String type, String sku,
            String name) {
        return insertProduct(storeId, externalId, parentId, type, sku, name, "publish");
    }

    private long insertProduct(long storeId, long externalId, Long parentId, String type, String sku,
            String name, String status) {
        ProductEntity entity = new ProductEntity();
        entity.setTenantId(tenantId);
        entity.setStoreId(storeId);
        entity.setExternalId(externalId);
        entity.setParentId(parentId);
        entity.setType(type);
        entity.setSku(sku);
        entity.setName(name);
        entity.setStatus(status);
        entity.setSyncedAt(OffsetDateTime.now(ZoneOffset.UTC));
        productMapper.insert(entity);
        assertThat(entity.getId()).isNotNull();
        return entity.getId();
    }

    private long insertMedia(long productId, String shotCode, String kind, String status,
            String... reasons) {
        StringBuilder reasonsJson = new StringBuilder();
        for (String reason : reasons) {
            if (reasonsJson.length() > 0) {
                reasonsJson.append(',');
            }
            reasonsJson.append('"').append(reason).append('"');
        }
        long seq = ++mediaSeq;
        String sha256 = String.format("%064d", seq);
        String objectKey = "t" + tenantId + "/source-media/" + productId + "/" + sha256 + ".jpg";
        String thumbObjectKey = "VIDEO".equals(kind) ? null : "t" + tenantId + "/thumbs/" + sha256 + ".jpg";
        return jdbcTemplate.queryForObject(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, thumb_object_key, content_type, size_bytes, sha256, qc_json, status) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) returning id",
                Long.class, tenantId, productId, shotCode, kind, "MG-X_" + shotCode + ".jpg", objectKey,
                thumbObjectKey, "image/jpeg", 1024, sha256,
                "{\"reasons\":[" + reasonsJson + "]}", status);
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

    private static JsonNode findLine(JsonNode root, String code) {
        for (JsonNode line : root.get("lines")) {
            if (code.equals(line.get("code").asText())) {
                return line;
            }
        }
        throw new AssertionError("line not found: " + code);
    }
}
