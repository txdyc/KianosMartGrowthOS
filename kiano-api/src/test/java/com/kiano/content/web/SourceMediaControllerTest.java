package com.kiano.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kiano.TestcontainersConfiguration;
import com.kiano.commerce.persistence.ProductEntity;
import com.kiano.commerce.persistence.ProductMapper;
import com.kiano.content.qc.PhotoQcTestImages;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * POST /api/v1/content/source-media: multipart upload with the unified 422
 * error codes for name/SKU/decoding problems and the OPERATOR gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class SourceMediaControllerTest {

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

    @TempDir
    Path tempDir;

    private long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from audit_log");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from comfy_workflow");
        jdbcTemplate.update("delete from app_user");
        insertUser(OPERATOR_EMAIL, OPERATOR_PASSWORD, "OPERATOR");
        insertUser(VIEWER_EMAIL, VIEWER_PASSWORD, "VIEWER");

        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        long storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) values (?, 'WOOCOMMERCE', 'https://woo.example.test') returning id",
                Long.class, tenantId);

        ProductEntity product = new ProductEntity();
        product.setTenantId(tenantId);
        product.setStoreId(storeId);
        product.setExternalId(301L);
        product.setParentId(null);
        product.setType("simple");
        product.setSku("MG-BL200");
        product.setName("Product MG-BL200");
        product.setStatus("publish");
        product.setSyncedAt(OffsetDateTime.now(ZoneOffset.UTC));
        productMapper.insert(product);
    }

    private void insertUser(String email, String password, String role) {
        jdbcTemplate.update(
                "insert into app_user (tenant_id, email, name, password_hash, role) values (?, ?, ?, ?, ?)",
                tenantId, email, "User " + role, passwordEncoder.encode(password), role);
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
    void unknownSku_422() throws Exception {
        String token = loginViaPost(OPERATOR_EMAIL, OPERATOR_PASSWORD);

        mockMvc.perform(multipart("/api/v1/content/source-media")
                        .file(new MockMultipartFile("file", "MG-NOPE_P1.jpg", "image/jpeg", jpegBytes()))
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SKU"))
                .andExpect(jsonPath("$.details.fileName").value("MG-NOPE_P1.jpg"));
    }

    @Test
    void badName_422() throws Exception {
        String token = loginViaPost(OPERATOR_EMAIL, OPERATOR_PASSWORD);

        mockMvc.perform(multipart("/api/v1/content/source-media")
                        .file(new MockMultipartFile("file", "MG-BL200_P1 (2).jpg", "image/jpeg", jpegBytes()))
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FILE_NAME"))
                .andExpect(jsonPath("$.details.fileName").value("MG-BL200_P1 (2).jpg"));
    }

    @Test
    void heic_422() throws Exception {
        String token = loginViaPost(OPERATOR_EMAIL, OPERATOR_PASSWORD);

        mockMvc.perform(multipart("/api/v1/content/source-media")
                        .file(new MockMultipartFile("file", "MG-BL200_P1.heic", "image/heic",
                                "pretend heic bytes".getBytes(StandardCharsets.UTF_8)))
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE_TYPE"))
                .andExpect(jsonPath("$.details.extension").value("heic"));
    }

    @Test
    void corruptJpeg_422() throws Exception {
        String token = loginViaPost(OPERATOR_EMAIL, OPERATOR_PASSWORD);

        mockMvc.perform(multipart("/api/v1/content/source-media")
                        .file(new MockMultipartFile("file", "MG-BL200_P5.jpg", "image/jpeg",
                                "definitely not a jpeg ".repeat(20).getBytes(StandardCharsets.UTF_8)))
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNREADABLE_MEDIA"));
    }

    @Test
    void viewer_403() throws Exception {
        String token = loginViaPost(VIEWER_EMAIL, VIEWER_PASSWORD);

        mockMvc.perform(multipart("/api/v1/content/source-media")
                        .file(new MockMultipartFile("file", "MG-BL200_P5.jpg", "image/jpeg", jpegBytes()))
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isForbidden());
    }

    private byte[] jpegBytes() throws Exception {
        Path file = tempDir.resolve("good.jpg");
        PhotoQcTestImages.writeJpeg(new PhotoQcTestImages.Checkerboard(2400, 3200), file);
        return Files.readAllBytes(file);
    }
}
