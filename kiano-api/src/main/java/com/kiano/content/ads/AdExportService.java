package com.kiano.content.ads;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.ContentTier;
import com.kiano.content.publish.PublicationEntity;
import com.kiano.content.publish.PublicationMapper;
import com.kiano.content.profile.ProductProfileService;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.queue.TaskQueue;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * C4a export request (spec §10.2): an empty product list means every HERO
 * product. A PENDING publication (target AD_EXPORT, environment PRODUCTION,
 * asset_ids []) is recorded as the export handle and an AD_EXPORT task is
 * enqueued with the resolved product ids; the task then fills the actual
 * asset ids, the ZIP location and the APPLIED/failed status.
 */
@Service
public class AdExportService {

    private final PublicationMapper publicationMapper;
    private final ProductCatalog productCatalog;
    private final ProductProfileService profileService;
    private final TaskQueue queue;
    private final ObjectStorage storage;
    private final ObjectMapper objectMapper;

    public AdExportService(PublicationMapper publicationMapper, ProductCatalog productCatalog,
            ProductProfileService profileService, TaskQueue queue, ObjectStorage storage,
            ObjectMapper objectMapper) {
        this.publicationMapper = publicationMapper;
        this.productCatalog = productCatalog;
        this.profileService = profileService;
        this.queue = queue;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    /** Requests an ad export; empty {@code productIds} exports every HERO product. */
    public long request(CurrentUser user, @Nullable List<Long> productIds) {
        List<Long> resolved = resolve(user.tenantId(), productIds);
        if (resolved.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOTHING_TO_EXPORT",
                    "No HERO product is available to export");
        }
        PublicationEntity entity = new PublicationEntity();
        entity.setTenantId(user.tenantId());
        entity.setProductId(resolved.get(0));
        entity.setEnvironment("PRODUCTION");
        entity.setTarget("AD_EXPORT");
        entity.setAssetIds("[]");
        entity.setUploadedMediaIds("[]");
        entity.setArchivedAssetIds("[]");
        entity.setStatus("PENDING");
        entity.setNeedsAttention(false);
        entity.setPublishedBy(user.userId());
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        publicationMapper.insert(entity);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("publicationId", entity.getId());
        payload.put("productIds", resolved);
        queue.enqueue(user.tenantId(), AdExportTaskHandler.TYPE, payload,
                "ad-export:" + String.join(",", resolved.stream().map(String::valueOf).toList()));
        return entity.getId();
    }

    /** The export history for a tenant, newest first. */
    public List<ExportView> list(long tenantId) {
        List<PublicationEntity> rows = publicationMapper.selectList(
                Wrappers.<PublicationEntity>lambdaQuery()
                        .eq(PublicationEntity::getTenantId, tenantId)
                        .eq(PublicationEntity::getTarget, "AD_EXPORT")
                        .orderByDesc(PublicationEntity::getId));
        List<ExportView> views = new ArrayList<>();
        for (PublicationEntity row : rows) {
            String after = row.getAfterJson();
            JsonNode afterNode = after == null ? null : objectMapper.readTree(after);
            views.add(new ExportView(row.getId(), row.getStatus(), row.getError(),
                    afterNode == null ? 0 : afterNode.path("fileCount").asInt(0),
                    afterNode == null ? null : afterNode.path("zipKey").asText(null),
                    row.getPublishedAt() == null ? null : row.getPublishedAt().toInstant()));
        }
        return views;
    }

    /** Download URL of an APPLIED export, presigned for 15 minutes. */
    public java.net.URI downloadUri(long tenantId, long publicationId) {
        PublicationEntity row = publicationMapper.selectById(publicationId);
        if (row == null || row.getTenantId() != tenantId
                || !"AD_EXPORT".equals(row.getTarget())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                    "Ad export not found");
        }
        if (!"APPLIED".equals(row.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "EXPORT_NOT_READY",
                    "The export has not been produced yet");
        }
        JsonNode after = objectMapper.readTree(row.getAfterJson());
        return storage.presignGet(after.path("zipKey").asText(), Duration.ofMinutes(15));
    }

    private List<Long> resolve(long tenantId, @Nullable List<Long> productIds) {
        if (productIds != null && !productIds.isEmpty()) {
            List<Long> resolved = new ArrayList<>();
            for (long id : productIds) {
                if (productCatalog.findById(tenantId, id).isEmpty()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                            "Product not found: " + id);
                }
                resolved.add(id);
            }
            return resolved;
        }
        List<Long> heroes = new ArrayList<>();
        for (ProductView product : productCatalog.listTopLevel(tenantId)) {
            if (profileService.tierOf(tenantId, product.id()) == ContentTier.HERO) {
                heroes.add(product.id());
            }
        }
        return heroes;
    }

    /** One ad export as shown in the /ads history list. */
    public record ExportView(long publicationId, String status, @Nullable String error,
            int fileCount, @Nullable String zipKey, @Nullable Instant publishedAt) {
    }
}