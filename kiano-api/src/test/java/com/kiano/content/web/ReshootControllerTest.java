package com.kiano.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kiano.TestcontainersConfiguration;
import com.kiano.commerce.persistence.ProductEntity;
import com.kiano.commerce.persistence.ProductMapper;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
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
 * GET /api/v1/content/reshoot-list (JSON) and reshoot-list.csv (UTF-8 BOM,
 * RFC4180 quoting, attachment disposition), both open to VIEWER.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ReshootControllerTest {

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

    private long tenantId;
    private long bl200Id;
    private long fan16Id;

    private long mediaSeq;

    @BeforeEach
    void setUp() {
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from comfy_workflow");
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
        long kettleCategory = jdbcTemplate.queryForObject(
                "insert into category (tenant_id, external_id, name, slug, synced_at) "
                        + "values (?, 401, 'Kettles', 'kettles', now()) returning id",
                Long.class, tenantId);
        jdbcTemplate.update("insert into product_category (product_id, category_id) values (?, ?)",
                bl200Id, kettleCategory);
        jdbcTemplate.update("insert into product_profile (product_id, tenant_id, role_source, content_tier) "
                + "values (?, ?, 'MANUAL', 'HERO')", bl200Id, tenantId);

        // MG-BL200 (HERO): all photos done except P3 (RESHOOT); videos missing.
        for (String code : new String[] {"P1", "P2", "P4", "P5", "P6", "P7", "P8", "P9"}) {
            insertMedia(bl200Id, code, "PHOTO", "ACCEPTED");
        }
        insertMedia(bl200Id, "P3", "PHOTO", "RESHOOT", "BLURRY");
        // MG-FAN16 (STANDARD): only P8 missing.
        for (String code : new String[] {"P1", "P2", "P3", "P4", "P5", "P6", "P7"}) {
            insertMedia(fan16Id, code, "PHOTO", "ACCEPTED");
        }
    }

    @Test
    void reshootList_tierFilterAndOrdering() throws Exception {
        String token = loginViaPost(VIEWER_EMAIL, VIEWER_PASSWORD);

        // HERO products first, then by sku; shots within a product by sort_order.
        mockMvc.perform(get("/api/v1/content/reshoot-list").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].sku").value("MG-BL200"))
                .andExpect(jsonPath("$[0].productName").value("Kettle, 1.7L"))
                .andExpect(jsonPath("$[0].tier").value("HERO"))
                .andExpect(jsonPath("$[0].shotCode").value("P3"))
                .andExpect(jsonPath("$[0].state").value("RESHOOT"))
                .andExpect(jsonPath("$[0].reasons[0]").value("BLURRY"))
                .andExpect(jsonPath("$[0].guidanceEn").value("Front-right 45°"))
                .andExpect(jsonPath("$[0].guidanceZh").value("右前 45°"))
                .andExpect(jsonPath("$[1].shotCode").value("V1"))
                .andExpect(jsonPath("$[1].state").value("MISSING"))
                .andExpect(jsonPath("$[1].guidanceZh").value("注水 → 烧开 → 自动断电"))
                .andExpect(jsonPath("$[2].shotCode").value("V2"))
                .andExpect(jsonPath("$[3].shotCode").value("V3"))
                .andExpect(jsonPath("$[4].sku").value("MG-FAN16"))
                .andExpect(jsonPath("$[4].tier").value("STANDARD"))
                .andExpect(jsonPath("$[4].shotCode").value("P8"))
                .andExpect(jsonPath("$[4].state").value("MISSING"));

        mockMvc.perform(get("/api/v1/content/reshoot-list").param("tier", "HERO")
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].sku").value("MG-BL200"))
                .andExpect(jsonPath("$[3].shotCode").value("V3"));

        mockMvc.perform(get("/api/v1/content/reshoot-list").param("tier", "STANDARD")
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sku").value("MG-FAN16"))
                .andExpect(jsonPath("$[0].shotCode").value("P8"));
    }

    @Test
    void reshootCsv_headerBomAndQuoting() throws Exception {
        String token = loginViaPost(VIEWER_EMAIL, VIEWER_PASSWORD);

        MvcResult result = mockMvc.perform(get("/api/v1/content/reshoot-list.csv")
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"reshoot-list.csv\""))
                .andReturn();

        String csv = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        // UTF-8 BOM followed by the exact fixed header line.
        assertThat(csv).startsWith(
                "\uFEFFsku,product_name,tier,shot_code,state,reasons,guidance_zh,guidance_en\r\n");
        // "Kettle, 1.7L" contains the delimiter and must be quoted; Chinese guidance as-is.
        assertThat(csv).contains(
                "MG-BL200,\"Kettle, 1.7L\",HERO,P3,RESHOOT,BLURRY,右前 45°,Front-right 45°\r\n");
        assertThat(csv).contains("MG-BL200,\"Kettle, 1.7L\",HERO,V1,MISSING,,注水 → 烧开 → 自动断电,"
                + "Fill with water → boil → automatic shut-off\r\n");
        // "Standing Fan 16in" has no delimiter, so RFC4180 leaves it unquoted.
        assertThat(csv).contains("MG-FAN16,Standing Fan 16in,STANDARD,P8,MISSING,,"
                + "配件全家福 + 包装盒,All accessories + packaging box\r\n");
    }

    private long insertProduct(long storeId, long externalId, Long parentId, String type, String sku,
            String name) {
        ProductEntity entity = new ProductEntity();
        entity.setTenantId(tenantId);
        entity.setStoreId(storeId);
        entity.setExternalId(externalId);
        entity.setParentId(parentId);
        entity.setType(type);
        entity.setSku(sku);
        entity.setName(name);
        entity.setStatus("publish");
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
}
