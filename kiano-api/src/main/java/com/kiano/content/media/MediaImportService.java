package com.kiano.content.media;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.qc.PhotoQc;
import com.kiano.content.qc.PhotoQcResult;
import com.kiano.content.qc.QcReason;
import com.kiano.content.qc.VideoInfo;
import com.kiano.content.qc.VideoProbe;
import com.kiano.content.qc.VideoQc;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Imports one shot file: parses the name, resolves the SKU (variations
 * attach to their parent product), dedupes by sha256, runs QC, uploads the
 * original (plus a photo thumbnail) and persists the record with audit in
 * one transaction. Re-takes of the same shot supersede the previous row.
 */
@Service
public class MediaImportService {

    private final ProductCatalog productCatalog;
    private final PhotoQc photoQc;
    private final VideoProbe videoProbe;
    private final VideoQc videoQc;
    private final ObjectStorage objectStorage;
    private final AuditLog auditLog;
    private final SourceMediaMapper sourceMediaMapper;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public MediaImportService(ProductCatalog productCatalog, PhotoQc photoQc, VideoProbe videoProbe,
            VideoQc videoQc, ObjectStorage objectStorage, AuditLog auditLog,
            SourceMediaMapper sourceMediaMapper, TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper) {
        this.productCatalog = productCatalog;
        this.photoQc = photoQc;
        this.videoProbe = videoProbe;
        this.videoQc = videoQc;
        this.objectStorage = objectStorage;
        this.auditLog = auditLog;
        this.sourceMediaMapper = sourceMediaMapper;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public ImportResult importFile(CurrentUser user, String originalFileName, Path tempFile, String contentType) {
        ParsedShotFile parsed = ShotFileName.parse(originalFileName);

        ProductView product = productCatalog.findBySku(user.tenantId(), parsed.sku())
                .orElseThrow(() -> unknownSku(originalFileName, parsed.sku()));
        if (product.parentId() != null) {
            product = productCatalog.findById(user.tenantId(), product.parentId())
                    .orElseThrow(() -> unknownSku(originalFileName, parsed.sku()));
        }
        long productId = product.id();

        String sha256 = sha256Hex(tempFile);
        SourceMediaEntity existing = findBySha256(user.tenantId(), sha256);
        if (existing != null) {
            return duplicate(originalFileName, user.tenantId(), existing);
        }

        QcOutcome qc = evaluate(parsed, tempFile);

        String objectKey = "t" + user.tenantId() + "/source-media/" + productId + "/" + sha256
                + "." + parsed.extension();
        objectStorage.put(objectKey, tempFile, contentType);
        boolean isVideo = parsed.kind() == MediaKind.VIDEO;
        final String thumbObjectKey = isVideo ? null
                : "t" + user.tenantId() + "/thumbs/" + sha256 + ".jpg";
        if (!isVideo) {
            objectStorage.put(thumbObjectKey, qc.thumbnail(), "image/jpeg");
        }

        long sizeBytes;
        try {
            sizeBytes = Files.size(tempFile);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNREADABLE_MEDIA",
                    "Cannot read the uploaded file: " + ex.getMessage());
        }

        try {
            return transactionTemplate.execute(status -> persist(user, originalFileName, parsed, productId,
                    sha256, objectKey, thumbObjectKey, sizeBytes, contentType, qc));
        } catch (DuplicateKeyException ex) {
            SourceMediaEntity raced = findBySha256(user.tenantId(), sha256);
            if (raced != null) {
                return duplicate(originalFileName, user.tenantId(), raced);
            }
            throw ex;
        }
    }

    private ImportResult persist(CurrentUser user, String originalFileName, ParsedShotFile parsed, long productId,
            String sha256, String objectKey, String thumbObjectKey, long sizeBytes, String contentType,
            QcOutcome qc) {
        sourceMediaMapper.update(null, Wrappers.<SourceMediaEntity>lambdaUpdate()
                .set(SourceMediaEntity::getStatus, "SUPERSEDED")
                .eq(SourceMediaEntity::getTenantId, user.tenantId())
                .eq(SourceMediaEntity::getProductId, productId)
                .eq(SourceMediaEntity::getShotCode, parsed.shotCode())
                .ne(SourceMediaEntity::getStatus, "SUPERSEDED"));

        String status = qc.reasons().isEmpty() ? "ACCEPTED" : "RESHOOT";
        SourceMediaEntity entity = new SourceMediaEntity();
        entity.setTenantId(user.tenantId());
        entity.setProductId(productId);
        entity.setShotCode(parsed.shotCode());
        entity.setKind(parsed.kind().name());
        entity.setOriginalFileName(originalFileName);
        entity.setObjectKey(objectKey);
        entity.setThumbObjectKey(thumbObjectKey);
        entity.setContentType(contentType);
        entity.setSizeBytes(sizeBytes);
        entity.setWidth(qc.width());
        entity.setHeight(qc.height());
        entity.setDurationS(qc.durationSeconds());
        entity.setSha256(sha256);
        entity.setQcJson(objectMapper.writeValueAsString(qc.qcJson()));
        entity.setStatus(status);
        entity.setUploadedBy(user.userId());
        sourceMediaMapper.insert(entity);

        auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER, String.valueOf(user.userId()),
                "SOURCE_MEDIA_IMPORTED", "source_media", String.valueOf(entity.getId()), null,
                Map.of("sku", parsed.sku(), "shotCode", parsed.shotCode(), "status", status,
                        "reasons", qc.reasons().stream().map(Enum::name).toList()),
                null, null));

        return new ImportResult(originalFileName, ImportResult.OUTCOME_IMPORTED, entity.getId(), productId,
                parsed.sku(), parsed.shotCode(), status, qc.reasons());
    }

    private QcOutcome evaluate(ParsedShotFile parsed, Path tempFile) {
        if (parsed.kind() == MediaKind.VIDEO) {
            VideoInfo info = videoProbe.probe(tempFile);
            List<QcReason> reasons = videoQc.evaluate(parsed.shotCode(), info);
            Map<String, Object> qcJson = new HashMap<>();
            qcJson.put("fps", info.fps());
            qcJson.put("durationSeconds", info.durationSeconds());
            qcJson.put("reasons", reasons.stream().map(Enum::name).toList());
            return new QcOutcome(info.width(), info.height(),
                    BigDecimal.valueOf(info.durationSeconds()).setScale(2, RoundingMode.HALF_UP),
                    reasons, qcJson, null);
        }
        PhotoQcResult result = photoQc.evaluate(tempFile, parsed.kind() != MediaKind.PROMO_IMAGE);
        Map<String, Object> qcJson = new HashMap<>();
        qcJson.put("exifOrientation", result.exifOrientation());
        qcJson.put("blurVariance", result.blurVariance());
        qcJson.put("overRatio", result.overRatio());
        qcJson.put("underRatio", result.underRatio());
        qcJson.put("reasons", result.reasons().stream().map(Enum::name).toList());
        return new QcOutcome(result.width(), result.height(), null, result.reasons(), qcJson,
                result.thumbnailJpeg());
    }

    private SourceMediaEntity findBySha256(long tenantId, String sha256) {
        return sourceMediaMapper.selectOne(Wrappers.<SourceMediaEntity>lambdaQuery()
                .eq(SourceMediaEntity::getTenantId, tenantId)
                .eq(SourceMediaEntity::getSha256, sha256)
                .last("limit 1"));
    }

    private ImportResult duplicate(String fileName, long tenantId, SourceMediaEntity existing) {
        String sku = productCatalog.findById(tenantId, existing.getProductId())
                .map(ProductView::sku)
                .orElse("");
        return new ImportResult(fileName, ImportResult.OUTCOME_DUPLICATE, existing.getId(),
                existing.getProductId(), sku, existing.getShotCode(), existing.getStatus(), List.of());
    }

    private static ApiException unknownSku(String fileName, String sku) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_SKU",
                "No product with SKU '" + sku + "'. Sync products first or fix the file name.",
                Map.of("fileName", fileName));
    }

    private static String sha256Hex(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNREADABLE_MEDIA",
                    "Cannot read the uploaded file: " + ex.getMessage());
        }
    }

    private record QcOutcome(Integer width, Integer height, BigDecimal durationSeconds, List<QcReason> reasons,
            Map<String, Object> qcJson, byte[] thumbnail) {
    }
}
