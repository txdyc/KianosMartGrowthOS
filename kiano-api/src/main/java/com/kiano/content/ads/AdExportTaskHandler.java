package com.kiano.content.ads;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.ads.ManifestCsv.Row;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.publish.PublicationEntity;
import com.kiano.content.publish.PublicationMapper;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import com.kiano.platform.queue.TaskHandler;
import com.kiano.platform.storage.ObjectStorage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Queue handler for AD_EXPORT (spec §10.2): for every requested product the
 * twelve latest APPROVED AD_STATIC variants are zipped under their file names
 * together with a manifest.csv; a SKU missing any variant is listed in
 * {@code skipped} with the missing variants. When no SKU qualifies the
 * publication fails with NOTHING_TO_EXPORT. The ZIP goes to
 * {@code t{tenant}/exports/ads/{publicationId}.zip} and the publication is
 * marked APPLIED with the exported asset ids and the zip key.
 */
@Component
public class AdExportTaskHandler implements TaskHandler {

    public static final String TYPE = "AD_EXPORT";
    private static final Logger log = LoggerFactory.getLogger(AdExportTaskHandler.class);

    private final AdRenderService renderService;
    private final AssetMapper assetMapper;
    private final ProductCatalog productCatalog;
    private final PublicationMapper publicationMapper;
    private final ObjectStorage storage;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;

    public AdExportTaskHandler(AdRenderService renderService, AssetMapper assetMapper,
            ProductCatalog productCatalog, PublicationMapper publicationMapper,
            ObjectStorage storage, AuditLog auditLog, ObjectMapper objectMapper) {
        this.renderService = renderService;
        this.assetMapper = assetMapper;
        this.productCatalog = productCatalog;
        this.publicationMapper = publicationMapper;
        this.storage = storage;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object handle(TaskContext ctx) {
        long publicationId = ctx.payload().path("publicationId").asLong();
        PublicationEntity publication = publicationMapper.selectById(publicationId);
        if (publication == null) {
            throw new NonRetryableTaskException("PUBLICATION_NOT_FOUND: " + publicationId);
        }
        long tenantId = publication.getTenantId();
        List<Long> productIds = new ArrayList<>();
        ctx.payload().path("productIds").forEach(node -> productIds.add(node.asLong()));

        List<Row> manifest = new ArrayList<>();
        List<Long> exportedAssetIds = new ArrayList<>();
        Map<String, Object> skippedBySku = new LinkedHashMap<>();
        List<byte[]> blobs = new ArrayList<>();
        for (long productId : productIds) {
            ProductView product = productCatalog.findById(tenantId, productId).orElse(null);
            if (product == null) {
                skippedBySku.put(String.valueOf(productId),
                        Map.of("reason", "PRODUCT_NOT_FOUND"));
                continue;
            }
            List<String> missing = new ArrayList<>();
            List<AssetEntity> statics = new ArrayList<>();
            for (AdRenderService.AdVariant variant : renderService.allVariants()) {
                Optional<AssetEntity> approved = latestApprovedStatic(tenantId, productId,
                        variant.variant());
                if (approved.isEmpty()) {
                    missing.add(variant.variant());
                } else {
                    statics.add(approved.get());
                }
            }
            if (!missing.isEmpty()) {
                skippedBySku.put(product.sku(),
                        Map.of("reason", "NOT_ALL_APPROVED", "missing", missing));
                continue;
            }
            for (int i = 0; i < statics.size(); i++) {
                AdRenderService.AdVariant variant = renderService.allVariants().get(i);
                AssetEntity asset = statics.get(i);
                AdCopyText copy = adCopyText(tenantId, asset);
                manifest.add(new Row(asset.getFileName(), product.sku(), variant.hook().wire(),
                        variant.size().label(), copy == null ? null : copy.headline(),
                        copy == null ? null : copy.primaryText(), asset.getPriceSnapshot(),
                        asset.getId()));
                exportedAssetIds.add(asset.getId());
                blobs.add(storage.download(asset.getObjectKey()));
            }
        }
        if (manifest.isEmpty()) {
            publication.setStatus("FAILED");
            publication.setError("NOTHING_TO_EXPORT: no SKU has the twelve approved variants");
            publicationMapper.updateById(publication);
            throw new NonRetryableTaskException(
                    "NOTHING_TO_EXPORT: no SKU has the twelve approved variants");
        }

        byte[] zip = zip(manifest, blobs);
        String zipKey = "t" + tenantId + "/exports/ads/" + publicationId + ".zip";
        storage.put(zipKey, zip, "application/zip");

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("zipKey", zipKey);
        after.put("skipped", skippedBySku);
        after.put("fileCount", manifest.size());
        publication.setAssetIds(objectMapper.writeValueAsString(exportedAssetIds));
        publication.setAfterJson(objectMapper.writeValueAsString(after));
        publication.setStatus("APPLIED");
        publication.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        publicationMapper.updateById(publication);

        auditLog.record(new AuditEntry(tenantId, ActorType.SYSTEM, "SYSTEM", "ADS_EXPORTED",
                "publication", String.valueOf(publicationId), null, after, null, "ADS"));
        log.info("Exported {} ad statics for publication {}", manifest.size(), publicationId);
        return Map.of("publicationId", publicationId, "fileCount", manifest.size(),
                "skipped", skippedBySku);
    }

    /** Zips the jpegs under their file names plus manifest.csv, in manifest order. */
    private byte[] zip(List<Row> manifest, List<byte[]> blobs) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < manifest.size(); i++) {
                zip.putNextEntry(new ZipEntry(manifest.get(i).fileName()));
                zip.write(blobs.get(i));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("manifest.csv"));
            zip.write(ManifestCsv.write(manifest));
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** The AD_COPY JSON the static was rendered with; null when unreadable. */
    private @Nullable AdCopyText adCopyText(long tenantId, AssetEntity asset) {
        try {
            JsonNode provenance = objectMapper.readTree(asset.getProvenanceJson());
            long copyId = provenance.path("adCopyAssetId").asLong(0);
            AssetEntity copy = copyId == 0 ? null : assetMapper.selectById(copyId);
            if (copy == null || copy.getTextBody() == null) {
                return null;
            }
            return AdCopyText.fromJson(copy.getTextBody());
        } catch (RuntimeException ex) {
            log.warn("Cannot read ad copy for asset {}", asset.getId(), ex);
            return null;
        }
    }

    private Optional<AssetEntity> latestApprovedStatic(long tenantId, long productId,
            String variant) {
        List<AssetEntity> rows = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "AD_STATIC")
                .eq(AssetEntity::getVariant, variant)
                .eq(AssetEntity::getStatus, AssetStatus.APPROVED.name())
                .orderByDesc(AssetEntity::getVersion)
                .last("limit 1"));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}