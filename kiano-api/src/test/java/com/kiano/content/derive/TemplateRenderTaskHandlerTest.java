package com.kiano.content.derive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.TestcontainersConfiguration;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.derive.TemplateRenderTaskHandlerTest.FakeGatewayConfig;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactsJson;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.llm.FakeLlmGateway;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import com.kiano.platform.storage.ObjectStorage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * TemplateRenderTaskHandler: PAGE_SPEC/PAGE_INFO render to a 1600×1600 JPEG
 * IN_REVIEW asset with factVersion; stale facts skip; PAGE_INFO without an
 * approved main fails.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FakeGatewayConfig.class})
class TemplateRenderTaskHandlerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TemplateRenderTaskHandler handler;

    @Autowired
    private FactSheetService factSheetService;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper mapper;

    private long tenantId;
    private long storeId;
    private long productId;
    private int factVersion;

    @TestConfiguration
    static class FakeGatewayConfig {
        @Bean
        @Primary
        FakeLlmGateway fakeLlmGateway() {
            return new FakeLlmGateway();
        }
    }

    @BeforeEach
    void seed() throws Exception {
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from llm_call");
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
                        + "values (?, ?, -6001, 'simple', 'MG-KTL17', 'Kettle 1.7L', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
        long userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'render-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        FactsJson facts = new FactsJson("MG-KTL17", "Electric Kettles", "1.7 L", 350,
                "220-240V", "Stainless steel", "Silver", "1 year", List.of("Kettle", "Base"),
                List.of("Auto shut-off"), List.of(), List.of());
        factSheetService.saveDraft(new CurrentUser(userId, tenantId, Role.OPERATOR,
                "render-op@example.test"), productId, facts,
                Map.of("model", com.kiano.content.facts.FieldSource.P5));
        factVersion = factSheetService.lock(new CurrentUser(userId, tenantId, Role.OPERATOR,
                "render-op@example.test"), productId, 1,
                Set.of("model", "capacity", "powerW", "voltage", "warranty", "inBox")).version();
    }

    private TaskContext ctx(String spec, int version) {
        return new TaskContext(1L, tenantId, mapper.createObjectNode()
                .put("productId", productId)
                .put("factVersion", version)
                .put("spec", spec), 1);
    }

    @Test
    void templateRender_spec_createsInReviewAssetWithFactVersion() throws Exception {
        handler.handle(ctx("PAGE_SPEC", factVersion));

        AssetEntity asset = assetMapper.selectOne(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "PAGE_SPEC")
                .last("limit 1"));
        assertThat(asset.getKind()).isEqualTo("IMAGE");
        assertThat(asset.getStatus()).isEqualTo("IN_REVIEW");
        assertThat(asset.getFactVersion()).isEqualTo(factVersion);
        assertThat(asset.getWidth()).isEqualTo(1600);
        assertThat(asset.getHeight()).isEqualTo(1600);
        BufferedImage image = ImageCodec.read(storage.download(asset.getObjectKey()));
        assertThat(image.getWidth()).isEqualTo(1600);
        assertThat(image.getHeight()).isEqualTo(1600);
        assertThat(asset.getFileName()).contains("page-spec");
    }

    @Test
    void templateRender_staleFacts_skipped() throws Exception {
        Object out = handler.handle(ctx("PAGE_SPEC", factVersion + 1));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) out;
        assertThat(result).containsEntry("skipped", "STALE_FACTS");
        assertThat(assetMapper.selectList(null)).isEmpty();
    }

    @Test
    void templateRender_info_requiresApprovedMain() {
        assertThatThrownBy(() -> handler.handle(ctx("PAGE_INFO", factVersion)))
                .isInstanceOf(NonRetryableTaskException.class)
                .hasMessageContaining("NO_MAIN_IMAGE");
    }

    @Test
    void templateRender_info_withMain_renders() throws Exception {
        byte[] png = jpegPng();
        String key = "t" + tenantId + "/assets/" + productId + "/PAGE_MAIN/main/v1.jpg";
        storage.put(key, png, "image/png");
        jdbcTemplate.update(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "object_key, status, precheck_json, provenance_json, created_at) "
                        + "values (?, ?, 'PAGE_MAIN', 'main', 1, 'IMAGE', ?, 'APPROVED', '{}', '{}', now())",
                tenantId, productId, key);

        handler.handle(ctx("PAGE_INFO", factVersion));

        AssetEntity asset = assetMapper.selectOne(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "PAGE_INFO")
                .last("limit 1"));
        assertThat(asset.getWidth()).isEqualTo(1600);
        assertThat(asset.getFileName()).contains("page-info");
        assertThat(asset.getProvenanceJson()).contains("mainAssetId");
    }

    private static byte[] jpegPng() throws Exception {
        BufferedImage image = new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1600, 1600);
        g.setColor(Color.BLACK);
        g.fillOval(200, 200, 1200, 1200);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}