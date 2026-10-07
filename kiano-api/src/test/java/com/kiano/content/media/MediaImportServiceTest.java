package com.kiano.content.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.kiano.TestcontainersConfiguration;
import com.kiano.commerce.persistence.ProductEntity;
import com.kiano.commerce.persistence.ProductMapper;
import com.kiano.content.qc.PhotoQcTestImages;
import com.kiano.content.qc.QcReason;
import com.kiano.content.qc.VideoInfo;
import com.kiano.content.qc.VideoProbe;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.storage.ObjectStorage;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * MediaImportService: dedupe by sha256, QC rules, variation SKUs attaching
 * to the parent product, supersede on re-take and the upload + audit flow.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class MediaImportServiceTest {

    @Autowired
    private MediaImportService service;

    @Autowired
    private ObjectStorage objectStorage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private VideoProbe videoProbe;

    @TempDir
    Path tempDir;

    private long tenantId;
    private long bl200Id;
    private long fan16Id;
    private CurrentUser operator;

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
        long userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'importer@example.test', 'Importer', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("importer-pass-123"));
        operator = new CurrentUser(userId, tenantId, Role.OPERATOR, "importer@example.test");

        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        long storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) values (?, 'WOOCOMMERCE', 'https://woo.example.test') returning id",
                Long.class, tenantId);

        bl200Id = insertProduct(storeId, 301L, null, "simple", "MG-BL200");
        fan16Id = insertProduct(storeId, 302L, null, "variable", "MG-FAN16");
        insertProduct(storeId, 3021L, fan16Id, "variation", "MG-FAN16-BLK");
    }

    private long insertProduct(long storeId, Long externalId, Long parentId, String type, String sku) {
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
        return entity.getId();
    }

    @Test
    void importGoodPhoto_acceptedStoredAudited() throws Exception {
        Path file = jpeg("MG-BL200_P5.jpg", new PhotoQcTestImages.Checkerboard(2400, 3200));

        ImportResult result = service.importFile(operator, "MG-BL200_P5.jpg", file, "image/jpeg");

        assertThat(result.outcome()).isEqualTo("IMPORTED");
        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.productId()).isEqualTo(bl200Id);
        assertThat(result.sku()).isEqualTo("MG-BL200");
        assertThat(result.shotCode()).isEqualTo("P5");
        assertThat(result.mediaId()).isPositive();
        assertThat(result.reasons()).isEmpty();

        String sha256 = sha256(file);
        assertThat(objectStorage.exists("t" + tenantId + "/source-media/" + bl200Id + "/" + sha256 + ".jpg"))
                .isTrue();
        assertThat(objectStorage.exists("t" + tenantId + "/thumbs/" + sha256 + ".jpg")).isTrue();

        Integer audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'SOURCE_MEDIA_IMPORTED'", Integer.class);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void sameBytesAgain_isDuplicate() throws Exception {
        Path file = jpeg("MG-BL200_P5.jpg", new PhotoQcTestImages.Checkerboard(2400, 3200));
        ImportResult first = service.importFile(operator, "MG-BL200_P5.jpg", file, "image/jpeg");

        ImportResult second = service.importFile(operator, "MG-BL200_P5.jpg", file, "image/jpeg");

        assertThat(second.outcome()).isEqualTo("DUPLICATE");
        assertThat(second.mediaId()).isEqualTo(first.mediaId());
        assertThat(second.status()).isEqualTo("ACCEPTED");

        Integer rows = jdbcTemplate.queryForObject("select count(*) from source_media", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void newTakeOfSameShot_supersedesOld() throws Exception {
        ImportResult first = service.importFile(operator, "MG-BL200_P5.jpg",
                jpeg("take1.jpg", new PhotoQcTestImages.Checkerboard(2400, 3200)), "image/jpeg");

        ImportResult second = service.importFile(operator, "MG-BL200_P5.jpg",
                jpeg("take2.jpg", new PhotoQcTestImages.Checkerboard(2400, 3200, 16)), "image/jpeg");

        assertThat(second.outcome()).isEqualTo("IMPORTED");
        assertThat(second.mediaId()).isNotEqualTo(first.mediaId());

        String oldStatus = jdbcTemplate.queryForObject(
                "select status from source_media where id = " + first.mediaId(), String.class);
        String newStatus = jdbcTemplate.queryForObject(
                "select status from source_media where id = " + second.mediaId(), String.class);
        assertThat(oldStatus).isEqualTo("SUPERSEDED");
        assertThat(newStatus).isEqualTo("ACCEPTED");
    }

    @Test
    void lowResPhoto_reshootWithReason() throws Exception {
        Path file = jpeg("MG-BL200_P5.jpg", new PhotoQcTestImages.Checkerboard(1500, 1000));

        ImportResult result = service.importFile(operator, "MG-BL200_P5.jpg", file, "image/jpeg");

        assertThat(result.status()).isEqualTo("RESHOOT");
        assertThat(result.reasons()).containsExactly(QcReason.LOW_RESOLUTION);
    }

    @Test
    void variationSku_attachesToParent() throws Exception {
        Path file = jpeg("MG-FAN16-BLK_P1.jpg", new PhotoQcTestImages.Checkerboard(2400, 3200));

        ImportResult result = service.importFile(operator, "MG-FAN16-BLK_P1.jpg", file, "image/jpeg");

        assertThat(result.outcome()).isEqualTo("IMPORTED");
        assertThat(result.productId()).isEqualTo(fan16Id);
    }

    @Test
    void exifRotated_storesDisplayDimensions() throws Exception {
        Path file = jpeg("MG-BL200_P5.jpg", new PhotoQcTestImages.Checkerboard(4000, 3000));
        addExifOrientation(file, (short) 6);

        ImportResult result = service.importFile(operator, "MG-BL200_P5.jpg", file, "image/jpeg");

        assertThat(result.status()).isEqualTo("ACCEPTED");
        Integer width = jdbcTemplate.queryForObject(
                "select width from source_media where id = " + result.mediaId(), Integer.class);
        Integer height = jdbcTemplate.queryForObject(
                "select height from source_media where id = " + result.mediaId(), Integer.class);
        assertThat(width).isLessThan(height);
        assertThat(width).isEqualTo(3000);
        assertThat(height).isEqualTo(4000);
    }

    @Test
    void video_v1_acceptedViaProbe() throws Exception {
        Path file = tempDir.resolve("MG-FAN16_V1.mp4");
        Files.writeString(file, "fake video payload ".repeat(20), StandardCharsets.UTF_8);
        when(videoProbe.probe(any())).thenReturn(new VideoInfo(1080, 1920, 12.0, 30.0));

        ImportResult result = service.importFile(operator, "MG-FAN16_V1.mp4", file, "video/mp4");

        assertThat(result.outcome()).isEqualTo("IMPORTED");
        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.reasons()).isEmpty();

        BigDecimal duration = jdbcTemplate.queryForObject(
                "select duration_s from source_media where id = " + result.mediaId(), BigDecimal.class);
        assertThat(duration.compareTo(new BigDecimal("12.00"))).isZero();
        Integer width = jdbcTemplate.queryForObject(
                "select width from source_media where id = " + result.mediaId(), Integer.class);
        Integer height = jdbcTemplate.queryForObject(
                "select height from source_media where id = " + result.mediaId(), Integer.class);
        assertThat(width).isEqualTo(1080);
        assertThat(height).isEqualTo(1920);
    }

    @Test
    void promo_neverReshoot() throws Exception {
        Path file = jpeg("MG-BL200_PROMO.jpg", new PhotoQcTestImages.Checkerboard(1000, 1000));

        ImportResult result = service.importFile(operator, "MG-BL200_PROMO.jpg", file, "image/jpeg");

        assertThat(result.shotCode()).isEqualTo("PROMO");
        assertThat(result.status()).isEqualTo("ACCEPTED");
        assertThat(result.reasons()).isEmpty();
    }

    private Path jpeg(String fileName, PhotoQcTestImages.Checkerboard checkerboard) throws Exception {
        Path file = tempDir.resolve(fileName);
        PhotoQcTestImages.writeJpeg(checkerboard, file);
        return file;
    }

    private static void addExifOrientation(Path file, short orientation) throws Exception {
        TiffOutputSet outputSet = new TiffOutputSet();
        outputSet.getOrCreateRootDirectory().add(TiffTagConstants.TIFF_TAG_ORIENTATION, orientation);
        byte[] jpeg = Files.readAllBytes(file);
        try (OutputStream out = Files.newOutputStream(file)) {
            new ExifRewriter().updateExifMetadataLossless(jpeg, out, outputSet);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
