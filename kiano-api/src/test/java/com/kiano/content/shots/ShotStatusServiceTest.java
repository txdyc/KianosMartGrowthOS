package com.kiano.content.shots;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.commerce.persistence.ProductEntity;
import com.kiano.commerce.persistence.ProductMapper;
import com.kiano.content.ContentTier;
import com.kiano.content.media.MediaKind;
import com.kiano.content.qc.QcReason;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * ShotStatusService: per-product checklist states (OK / RESHOOT / MISSING),
 * the complete flag, summaries with counts and query filter, and the reshoot
 * list. Media rows are inserted directly, bypassing the import flow.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ShotStatusServiceTest {

    @Autowired
    private ShotStatusService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        mediaSeq = 0;
        long storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) values (?, 'WOOCOMMERCE', 'https://woo.example.test') returning id",
                Long.class, tenantId);

        bl200Id = insertProduct(storeId, 301L, null, "simple", "MG-BL200", "Kettle, 1.7L", "publish",
                "199.00", "179.00", "179.00");
        fan16Id = insertProduct(storeId, 302L, null, "variable", "MG-FAN16", "Standing Fan 16in",
                "publish", null, null, null);
        insertProduct(storeId, 303L, null, "simple", "MG-GONE", "Disappeared product", "missing",
                null, null, null);
        insertProduct(storeId, 3021L, fan16Id, "variation", "MG-FAN16-BLK", "Fan black", "publish",
                null, null, null);
        long kettleCategory = jdbcTemplate.queryForObject(
                "insert into category (tenant_id, external_id, name, slug, synced_at) "
                        + "values (?, 401, 'Kettles', 'kettles', now()) returning id",
                Long.class, tenantId);
        jdbcTemplate.update("insert into product_category (product_id, category_id) values (?, ?)",
                bl200Id, kettleCategory);
        jdbcTemplate.update("insert into product_profile (product_id, tenant_id, role_source, content_tier) "
                + "values (?, ?, 'MANUAL', 'HERO')", bl200Id, tenantId);
    }

    @Test
    void standard_missingP8_incomplete() {
        for (String code : List.of("P1", "P2", "P3", "P4", "P5", "P6", "P7")) {
            insertMedia(fan16Id, code, "PHOTO", "ACCEPTED");
        }

        ProductShotStatus status = service.statusFor(tenantId, fan16Id, false);

        assertThat(status.sku()).isEqualTo("MG-FAN16");
        assertThat(status.tier()).isEqualTo(ContentTier.STANDARD);
        assertThat(status.lines()).hasSize(8);
        assertThat(status.complete()).isFalse();

        ShotStatusLine p8 = line(status, "P8");
        assertThat(p8.required()).isTrue();
        assertThat(p8.state()).isEqualTo(ShotState.MISSING);
        assertThat(p8.mediaId()).isNull();
        assertThat(p8.reasons()).isEmpty();

        ShotStatusLine p1 = line(status, "P1");
        assertThat(p1.state()).isEqualTo(ShotState.OK);
        assertThat(p1.mediaId()).isNotNull();
    }

    @Test
    void reshootLine_carriesReasons() {
        // Everything accepted except one reshoot-worthy take.
        for (String code : List.of("P1", "P2", "P4", "P5", "P6", "P7", "P8")) {
            insertMedia(fan16Id, code, "PHOTO", "ACCEPTED");
        }
        insertMedia(fan16Id, "P3", "PHOTO", "RESHOOT", "BLURRY");
        for (String code : List.of("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9")) {
            insertMedia(bl200Id, code, "PHOTO", "ACCEPTED");
        }
        for (String code : List.of("V1", "V2", "V3")) {
            insertMedia(bl200Id, code, "VIDEO", "ACCEPTED");
        }

        List<ReshootLine> reshootList = service.reshootList(tenantId, null);

        assertThat(reshootList).hasSize(1);
        ReshootLine reshoot = reshootList.get(0);
        assertThat(reshoot.sku()).isEqualTo("MG-FAN16");
        assertThat(reshoot.productName()).isEqualTo("Standing Fan 16in");
        assertThat(reshoot.tier()).isEqualTo(ContentTier.STANDARD);
        assertThat(reshoot.shotCode()).isEqualTo("P3");
        assertThat(reshoot.state()).isEqualTo(ShotState.RESHOOT);
        assertThat(reshoot.reasons()).containsExactly(QcReason.BLURRY);
        assertThat(reshoot.guidanceEn()).isEqualTo("Front-right 45°");
        assertThat(reshoot.guidanceZh()).isEqualTo("右前 45°");
    }

    @Test
    void allStandardOk_complete_thenHeroIncomplete() {
        for (String code : List.of("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8")) {
            insertMedia(fan16Id, code, "PHOTO", "ACCEPTED");
        }
        assertThat(service.statusFor(tenantId, fan16Id, false).complete()).isTrue();

        jdbcTemplate.update("insert into product_profile (product_id, tenant_id, role_source, content_tier) "
                + "values (?, ?, 'MANUAL', 'HERO')", fan16Id, tenantId);

        ProductShotStatus hero = service.statusFor(tenantId, fan16Id, false);
        assertThat(hero.tier()).isEqualTo(ContentTier.HERO);
        assertThat(hero.complete()).isFalse();
        assertThat(hero.lines()).hasSize(12);
        for (String code : List.of("P9", "V1", "V2", "V3")) {
            assertThat(line(hero, code).state()).isEqualTo(ShotState.MISSING);
        }
        assertThat(line(hero, "P1").state()).isEqualTo(ShotState.OK);
    }

    @Test
    void supersededReshoot_thenAccepted_isOk() {
        long acceptedId = insertMedia(fan16Id, "P1", "PHOTO", "ACCEPTED");
        long reshootId = insertMedia(fan16Id, "P1", "PHOTO", "RESHOOT", "BLURRY");
        jdbcTemplate.update("update source_media set status = 'SUPERSEDED' where id = ?", reshootId);

        ProductShotStatus status = service.statusFor(tenantId, fan16Id, false);

        ShotStatusLine p1 = line(status, "P1");
        assertThat(p1.state()).isEqualTo(ShotState.OK);
        assertThat(p1.mediaId()).isEqualTo(acceptedId);
        assertThat(p1.reasons()).isEmpty();
    }

    @Test
    void summaries_countsAndQueryFilter() {
        insertMedia(bl200Id, "P1", "PHOTO", "ACCEPTED");
        insertMedia(bl200Id, "P3", "PHOTO", "RESHOOT", "BLURRY");
        for (String code : List.of("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8")) {
            insertMedia(fan16Id, code, "PHOTO", "ACCEPTED");
        }

        List<ContentProductSummary> all = service.summaries(tenantId, null, null);

        // MG-GONE (status 'missing') and the variation are not listed.
        assertThat(all).extracting(ContentProductSummary::sku)
                .containsExactly("MG-BL200", "MG-FAN16");

        ContentProductSummary bl200 = all.get(0);
        assertThat(bl200.name()).isEqualTo("Kettle, 1.7L");
        assertThat(bl200.type()).isEqualTo("simple");
        assertThat(bl200.status()).isEqualTo("publish");
        assertThat(bl200.regularPrice()).isEqualByComparingTo("199.00");
        assertThat(bl200.salePrice()).isEqualByComparingTo("179.00");
        assertThat(bl200.price()).isEqualByComparingTo("179.00");
        assertThat(bl200.tier()).isEqualTo(ContentTier.HERO);
        assertThat(bl200.required()).isEqualTo(12);
        assertThat(bl200.ok()).isEqualTo(1);
        assertThat(bl200.reshoot()).isEqualTo(1);
        assertThat(bl200.missing()).isEqualTo(10);
        assertThat(bl200.complete()).isFalse();

        ContentProductSummary fan16 = all.get(1);
        assertThat(fan16.tier()).isEqualTo(ContentTier.STANDARD);
        assertThat(fan16.required()).isEqualTo(8);
        assertThat(fan16.ok()).isEqualTo(8);
        assertThat(fan16.reshoot()).isZero();
        assertThat(fan16.missing()).isZero();
        assertThat(fan16.complete()).isTrue();

        List<ContentProductSummary> byQuery = service.summaries(tenantId, null, "bl200");
        assertThat(byQuery).hasSize(1);
        assertThat(byQuery.get(0).sku()).isEqualTo("MG-BL200");

        List<ContentProductSummary> byTier = service.summaries(tenantId, ContentTier.STANDARD, null);
        assertThat(byTier).hasSize(1);
        assertThat(byTier.get(0).sku()).isEqualTo("MG-FAN16");
    }

    @Test
    void shotLines_carryBothGuidanceLanguages() {
        ProductShotStatus status = service.statusFor(tenantId, bl200Id, false);

        ShotStatusLine p1 = line(status, "P1");
        assertThat(p1.guidanceEn()).isEqualTo("Front, eye level");
        assertThat(p1.guidanceZh()).isEqualTo("正面，平视");

        // kettle category overrides the generic V1 guidance
        ShotStatusLine v1 = line(status, "V1");
        assertThat(v1.kind()).isEqualTo(MediaKind.VIDEO);
        assertThat(v1.guidanceEn()).isEqualTo("Fill with water → boil → automatic shut-off");
        assertThat(v1.guidanceZh()).isEqualTo("注水 → 烧开 → 自动断电");
    }

    private long insertProduct(long storeId, long externalId, Long parentId, String type, String sku,
            String name, String status, String regularPrice, String salePrice, String price) {
        ProductEntity entity = new ProductEntity();
        entity.setTenantId(tenantId);
        entity.setStoreId(storeId);
        entity.setExternalId(externalId);
        entity.setParentId(parentId);
        entity.setType(type);
        entity.setSku(sku);
        entity.setName(name);
        entity.setStatus(status);
        entity.setStockStatus("instock");
        if (regularPrice != null) {
            entity.setRegularPrice(new BigDecimal(regularPrice));
        }
        if (salePrice != null) {
            entity.setSalePrice(new BigDecimal(salePrice));
        }
        if (price != null) {
            entity.setPrice(new BigDecimal(price));
        }
        entity.setSyncedAt(OffsetDateTime.now(ZoneOffset.UTC));
        productMapper.insert(entity);
        assertThat(entity.getId()).isNotNull();
        return entity.getId();
    }

    private long insertMedia(long productId, String shotCode, String kind, String status,
            String... reasons) {
        String reasonsJson = Arrays.stream(reasons).map(reason -> "\"" + reason + "\"")
                .collect(Collectors.joining(","));
        long seq = ++mediaSeq;
        String sha256 = String.format("%064d", seq);
        String objectKey = "t" + tenantId + "/source-media/" + productId + "/" + sha256 + ".jpg";
        String thumbObjectKey = "VIDEO".equals(kind) ? null : "t" + tenantId + "/thumbs/" + sha256 + ".jpg";
        return jdbcTemplate.queryForObject(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, thumb_object_key, content_type, size_bytes, sha256, qc_json, status) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) returning id",
                Long.class, tenantId, productId, shotCode, kind, "MG-X_" + shotCode + ".jpg", objectKey,
                thumbObjectKey, "image/jpeg", 1024, sha256, "{\"reasons\":[" + reasonsJson + "]}",
                status);
    }

    private static ShotStatusLine line(ProductShotStatus status, String code) {
        return status.lines().stream().filter(line -> line.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("line not found: " + code));
    }
}
