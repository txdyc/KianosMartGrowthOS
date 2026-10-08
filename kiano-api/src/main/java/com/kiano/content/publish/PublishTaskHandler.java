package com.kiano.content.publish;

import com.kiano.commerce.ProductView;
import com.kiano.commerce.CommerceException;
import com.kiano.commerce.CommercePublisher;
import com.kiano.commerce.ProductContentUpdate;
import com.kiano.commerce.PublishEnvironment;
import com.kiano.commerce.WooMedia;
import com.kiano.commerce.WooProductSnapshot;
import com.kiano.commerce.woo.CommercePublisherFactory;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.asset.ReviewService;
import com.kiano.content.asset.ReviewService.ReviewItem;
import com.kiano.commerce.ProductCatalog;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import com.kiano.platform.queue.TaskHandler;
import com.kiano.platform.storage.ObjectStorage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Queue handler for PUBLISH_PRODUCT (spec §10.1): finds the product by SKU,
 * snapshots it, uploads the gallery media in order (persisting each uploaded
 * id immediately), writes name/descriptions/images/Rank Math meta, then
 * marks the publication APPLIED - and for PRODUCTION publishes the assets
 * and archives the superseded versions. Any failure triggers restore of the
 * before snapshot and deletion of the uploaded media; if even the restore
 * fails the publication is flagged needsAttention.
 */
@Component
public class PublishTaskHandler implements TaskHandler {

    public static final String TYPE = "PUBLISH_PRODUCT";
    private static final Logger log = LoggerFactory.getLogger(PublishTaskHandler.class);

    private final PublicationMapper publicationMapper;
    private final AssetMapper assetMapper;
    private final ObjectStorage storage;
    private final ReviewService reviewService;
    private final ProductCatalog productCatalog;
    private final CommercePublisherFactory publisherFactory;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;

    public PublishTaskHandler(PublicationMapper publicationMapper, AssetMapper assetMapper,
            ObjectStorage storage, ReviewService reviewService, ProductCatalog productCatalog,
            CommercePublisherFactory publisherFactory, AuditLog auditLog,
            ObjectMapper objectMapper) {
        this.publicationMapper = publicationMapper;
        this.assetMapper = assetMapper;
        this.storage = storage;
        this.reviewService = reviewService;
        this.productCatalog = productCatalog;
        this.publisherFactory = publisherFactory;
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
        long productId = publication.getProductId();
        PublishEnvironment environment = PublishEnvironment.valueOf(publication.getEnvironment());
        CommercePublisher publisher;
        try {
            publisher = publisherFactory.forEnvironment(tenantId, environment);
        } catch (RuntimeException ex) {
            fail(publication, ex.getMessage());
            throw new NonRetryableTaskException(ex.getMessage());
        }
        ProductView product = productCatalog.findById(tenantId, productId).orElse(null);
        if (product == null) {
            fail(publication, "WOO_PRODUCT_NOT_FOUND: local product missing");
            throw new NonRetryableTaskException("WOO_PRODUCT_NOT_FOUND");
        }
        Optional<WooProductSnapshot> bySku = publisher.findBySku(product.sku());
        if (bySku.isEmpty()) {
            fail(publication, "WOO_PRODUCT_NOT_FOUND: no product with sku " + product.sku());
            throw new NonRetryableTaskException("WOO_PRODUCT_NOT_FOUND");
        }
        WooProductSnapshot before = bySku.get();
        publication.setBeforeJson(objectMapper.writeValueAsString(before));
        update(publication);
        List<AssetEntity> publicationAssets = new ArrayList<>();
        try {
            List<ReviewItem> items = filesFor(productId, publication, tenantId);
            List<Long> mediaIds = new ArrayList<>();
            for (ReviewItem item : items) {
                AssetEntity asset = assetMapper.selectById(item.assetId());
                publicationAssets.add(asset);
                if ("IMAGE".equals(asset.getKind())) {
                    byte[] jpeg = storage.download(asset.getObjectKey());
                    WooMedia media = publisher.uploadMedia(asset.getFileName(), jpeg,
                            "image/jpeg", AltTextBuilder.build(product.name(), item));
                    mediaIds.add(media.id());
                    appendUploadedMedia(publication, media.id());
                }
            }
            List<ReviewItem> texts = textAssets(productId, tenantId);
            String name = textOf(texts, "COPY_TITLE");
            String description = textOf(texts, "COPY_LONG");
            String shortDescription = textOf(texts, "COPY_SHORT");
            ReviewItem seo = texts.stream()
                    .filter(item -> "COPY_SEO".equals(item.specCode())).findFirst().orElse(null);
            ProductContentUpdate update = new ProductContentUpdate(name, description,
                    shortDescription, mediaIds,
                    seoTitle(seo), seoDescription(seo));
            WooProductSnapshot after = publisher.updateContent(before.id(), update);
            if (!sameImageIds(before, after, update.imageIdsInOrder())) {
                throw new CommerceException("WOO_IMAGE_ORDER_CHANGED",
                        "Woo did not apply the image set in order", false);
            }
            publication.setAfterJson(objectMapper.writeValueAsString(after));
            publication.setExternalRef(String.valueOf(before.id()));
            publication.setStatus("APPLIED");
            publication.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            if (environment == PublishEnvironment.PRODUCTION) {
                publishAssets(publication, publicationAssets);
            }
            update(publication);
            auditLog.record(new AuditEntry(tenantId, ActorType.SYSTEM, "SYSTEM",
                    environment == PublishEnvironment.PRODUCTION ? "PUBLISHED" : "STAGING_APPLIED",
                    "publication", String.valueOf(publication.getId()),
                    Map.of("environment", environment.name()),
                    Map.of("productId", productId, "assetIds", publicationAssets.size(),
                            "mediaIds", mediaIds.size()),
                    null, "PUBLISH"));
            return Map.of("publicationId", publicationId, "status", "APPLIED",
                    "mediaUploaded", mediaIds.size());
        } catch (RuntimeException ex) {
            restoreAndFail(publication, publisher, ex);
            throw new NonRetryableTaskException(ex.getMessage());
        }
    }

    /** The ordered gallery files for this publication (from its asset_ids). */
    private List<ReviewItem> filesFor(long productId, PublicationEntity publication,
            long tenantId) {
        List<ReviewItem> approved = reviewService.list(new com.kiano.platform.auth.CurrentUser(0,
                tenantId, com.kiano.platform.auth.Role.OWNER, "system"), productId, "APPROVED",
                null);
        List<Long> wanted = readIdList(publication.getAssetIds());
        List<ReviewItem> gallery = GallerySelector.select(approved);
        List<ReviewItem> ordered = new ArrayList<>();
        for (ReviewItem item : gallery) {
            if (wanted.contains(item.assetId())) {
                ordered.add(item);
            }
        }
        return ordered;
    }

    private List<ReviewItem> textAssets(long productId, long tenantId) {
        return reviewService.list(new com.kiano.platform.auth.CurrentUser(0, tenantId,
                com.kiano.platform.auth.Role.OWNER, "system"), productId, "APPROVED", "TEXT");
    }

    private static String textOf(List<ReviewItem> texts, String spec) {
        return texts.stream().filter(item -> spec.equals(item.specCode()))
                .map(ReviewItem::textBody).filter(Objects::nonNull).findFirst().orElse("");
    }

    private static @org.jspecify.annotations.Nullable String seoTitle(ReviewItem seo) {
        return seo == null ? null : seoTitle(seo.textBody());
    }

    private static @org.jspecify.annotations.Nullable String seoDescription(ReviewItem seo) {
        return seo == null ? null : seoDescription(seo.textBody());
    }

    private static String seoTitle(String seoJson) {
        try {
            JsonNode node = new ObjectMapper().readTree(seoJson);
            return node.path("title").asText(null);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String seoDescription(String seoJson) {
        try {
            JsonNode node = new ObjectMapper().readTree(seoJson);
            return node.path("description").asText(null);
        } catch (Exception ex) {
            return null;
        }
    }

    static boolean sameImageIds(WooProductSnapshot before, WooProductSnapshot after,
            List<Long> expected) {
        List<Long> actual = after.images().stream().map(WooProductSnapshot.WooImageRef::id).toList();
        return actual.equals(expected);
    }

    /** PRODUCTION only: mark published & archive superseded versions. */
    @Transactional
    protected void publishAssets(PublicationEntity publication,
            List<AssetEntity> publicationAssets) {
        List<Long> archived = new ArrayList<>();
        for (AssetEntity asset : publicationAssets) {
            List<AssetEntity> superseded = assetMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AssetEntity>()
                            .eq(AssetEntity::getTenantId, asset.getTenantId())
                            .eq(AssetEntity::getProductId, asset.getProductId())
                            .eq(AssetEntity::getSpecCode, asset.getSpecCode())
                            .eq(AssetEntity::getVariant, asset.getVariant())
                            .eq(AssetEntity::getStatus, AssetStatus.PUBLISHED.name())
                            .ne(AssetEntity::getId, asset.getId())
                            .ne(AssetEntity::getVersion, asset.getVersion()));
            for (AssetEntity old : superseded) {
                old.setStatus(AssetStatus.ARCHIVED.name());
                assetMapper.updateById(old);
                archived.add(old.getId());
            }
            if (!AssetStatus.PUBLISHED.name().equals(asset.getStatus())) {
                asset.setStatus(AssetStatus.PUBLISHED.name());
                assetMapper.updateById(asset);
            }
        }
        if (!archived.isEmpty()) {
            publication.setArchivedAssetIds(objectMapper.writeValueAsString(archived));
        }
    }

    // ---- failure restore ----

    private void restoreAndFail(PublicationEntity publication, CommercePublisher publisher,
            Throwable cause) {
        try {
            String beforeJson = publication.getBeforeJson();
            if (beforeJson != null) {
                WooProductSnapshot before = objectMapper.readValue(beforeJson,
                        WooProductSnapshot.class);
                ProductContentUpdate restore = new ProductContentUpdate(before.name(),
                        before.description(), before.shortDescription(),
                        before.images().stream().map(WooProductSnapshot.WooImageRef::id).toList(),
                        before.rankMath() == null ? null : before.rankMath().title(),
                        before.rankMath() == null ? null : before.rankMath().description());
                publisher.updateContent(before.id(), restore);
            }
        } catch (RuntimeException restoreFailure) {
            publication.setNeedsAttention(true);
            publication.setError((publication.getError() == null ? "" : publication.getError())
                    + " | restore failed: " + restoreFailure.getMessage());
        }
        for (Long mediaId : readIdList(publication.getUploadedMediaIds())) {
            try {
                publisher.deleteMedia(mediaId);
            } catch (RuntimeException ex) {
                log.warn("Failed to delete uploaded media {} during restore", mediaId, ex);
            }
        }
        if (!Boolean.TRUE.equals(publication.getNeedsAttention())) {
            publication.setStatus("FAILED");
        } else {
            publication.setStatus("FAILED");
        }
        publication.setError(publication.getError() == null ? String.valueOf(cause.getMessage())
                : publication.getError() + " | " + cause.getMessage());
        update(publication);
    }

    private void fail(PublicationEntity publication, String error) {
        publication.setStatus("FAILED");
        publication.setError(error);
        update(publication);
    }

    private void appendUploadedMedia(PublicationEntity publication, long mediaId) {
        List<Long> ids = readIdList(publication.getUploadedMediaIds());
        ids.add(mediaId);
        publication.setUploadedMediaIds(objectMapper.writeValueAsString(ids));
        update(publication);
    }

    private List<Long> readIdList(String json) {
        List<Long> ids = new ArrayList<>();
        if (json != null && !json.isBlank()) {
            objectMapper.readTree(json).forEach(node -> ids.add(node.asLong()));
        }
        return ids;
    }

    private void update(PublicationEntity publication) {
        publicationMapper.updateById(publication);
    }
}