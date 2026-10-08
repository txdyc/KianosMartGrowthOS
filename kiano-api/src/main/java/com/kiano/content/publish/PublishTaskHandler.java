package com.kiano.content.publish;

import com.kiano.commerce.CommerceException;
import com.kiano.commerce.CommercePublisher;
import com.kiano.commerce.CommercePublisherFactory;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductContentUpdate;
import com.kiano.commerce.ProductView;
import com.kiano.commerce.PublishEnvironment;
import com.kiano.commerce.WooMedia;
import com.kiano.commerce.WooProductSnapshot;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.asset.ReviewService;
import com.kiano.content.asset.ReviewService.ReviewItem;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import com.kiano.platform.queue.TaskHandler;
import com.kiano.platform.storage.ObjectStorage;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Queue handler for PUBLISH_PRODUCT (spec §10.1): finds the product by SKU,
 * snapshots it, uploads the gallery media in order (persisting each uploaded
 * id immediately), writes name/descriptions/images/Rank Math meta, then
 * marks the publication APPLIED - and for PRODUCTION publishes the assets
 * and archives the superseded versions. What gets written is exactly the
 * asset set recorded on the publication (the set staging validated), never a
 * fresh query. Any failure triggers restore of the before snapshot and
 * deletion of the uploaded media; if even the restore fails the publication
 * is flagged needsAttention.
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
    private final TransactionTemplate transactionTemplate;

    public PublishTaskHandler(PublicationMapper publicationMapper, AssetMapper assetMapper,
            ObjectStorage storage, ReviewService reviewService, ProductCatalog productCatalog,
            CommercePublisherFactory publisherFactory, AuditLog auditLog,
            ObjectMapper objectMapper, TransactionTemplate transactionTemplate) {
        this.publicationMapper = publicationMapper;
        this.assetMapper = assetMapper;
        this.storage = storage;
        this.reviewService = reviewService;
        this.productCatalog = productCatalog;
        this.publisherFactory = publisherFactory;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
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
        try {
            List<ReviewItem> recorded = recordedItems(tenantId, productId, publication);
            List<ReviewItem> gallery = GallerySelector.select(recorded.stream()
                    .filter(item -> "IMAGE".equals(item.kind())).toList());
            List<ReviewItem> texts = recorded.stream()
                    .filter(item -> "TEXT".equals(item.kind())).toList();
            List<Long> mediaIds = new ArrayList<>();
            for (ReviewItem item : gallery) {
                AssetEntity asset = assetMapper.selectById(item.assetId());
                byte[] jpeg = storage.download(asset.getObjectKey());
                WooMedia media = publisher.uploadMedia(asset.getFileName(), jpeg, "image/jpeg",
                        AltTextBuilder.build(product.name(), item));
                mediaIds.add(media.id());
                appendUploadedMedia(publication, media.id());
            }
            ReviewItem seo = textItem(texts, "COPY_SEO");
            ProductContentUpdate update = new ProductContentUpdate(textOf(texts, "COPY_TITLE"),
                    textOf(texts, "COPY_LONG"), textOf(texts, "COPY_SHORT"), mediaIds,
                    seoField(seo, "title"), seoField(seo, "description"));
            WooProductSnapshot after = publisher.updateContent(before.id(), update);
            if (!sameImageIds(after, update.imageIdsInOrder())) {
                throw new CommerceException("WOO_IMAGE_ORDER_CHANGED",
                        "Woo did not apply the image set in order", false);
            }
            // Asset statuses, the publication row and the audit entry commit
            // together; any failure here rolls them back and restores Woo below.
            transactionTemplate.executeWithoutResult(tx -> {
                publication.setAfterJson(objectMapper.writeValueAsString(after));
                publication.setExternalRef(String.valueOf(before.id()));
                publication.setStatus("APPLIED");
                publication.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
                if (environment == PublishEnvironment.PRODUCTION) {
                    publishAssets(publication, recorded);
                }
                update(publication);
                auditLog.record(new AuditEntry(tenantId, ActorType.SYSTEM, "SYSTEM",
                        environment == PublishEnvironment.PRODUCTION ? "PUBLISHED"
                                : "STAGING_APPLIED",
                        "publication", String.valueOf(publication.getId()),
                        Map.of("environment", environment.name()),
                        Map.of("productId", productId, "assetIds", recorded.size(),
                                "mediaIds", mediaIds.size()),
                        null, "PUBLISH"));
            });
            return Map.of("publicationId", publicationId, "status", "APPLIED",
                    "mediaUploaded", mediaIds.size());
        } catch (RuntimeException ex) {
            restoreAndFail(publication, publisher, ex);
            throw new NonRetryableTaskException(ex.getMessage());
        }
    }

    /** Exactly the assets recorded on the publication, whatever their current status. */
    private List<ReviewItem> recordedItems(long tenantId, long productId,
            PublicationEntity publication) {
        Set<Long> wanted = new HashSet<>(readIdList(publication.getAssetIds()));
        CurrentUser system = new CurrentUser(0, tenantId, Role.OWNER, "system");
        return reviewService.list(system, productId, null, null).stream()
                .filter(item -> wanted.contains(item.assetId()))
                .toList();
    }

    private static @Nullable ReviewItem textItem(List<ReviewItem> texts, String spec) {
        return texts.stream().filter(item -> spec.equals(item.specCode())).findFirst()
                .orElse(null);
    }

    private static String textOf(List<ReviewItem> texts, String spec) {
        ReviewItem item = textItem(texts, spec);
        return item == null || item.textBody() == null ? "" : item.textBody();
    }

    /** title/description from the COPY_SEO JSON; null (meta untouched) when absent. */
    private @Nullable String seoField(@Nullable ReviewItem seo, String field) {
        if (seo == null || seo.textBody() == null) {
            return null;
        }
        try {
            return objectMapper.readTree(seo.textBody()).path(field).asText(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static boolean sameImageIds(WooProductSnapshot after, List<Long> expected) {
        List<Long> actual = after.images().stream().map(WooProductSnapshot.WooImageRef::id)
                .toList();
        return actual.equals(expected);
    }

    /** PRODUCTION only (inside the APPLIED transaction): publish every recorded asset. */
    private void publishAssets(PublicationEntity publication, List<ReviewItem> recorded) {
        List<Long> archived = new ArrayList<>();
        for (ReviewItem item : recorded) {
            AssetEntity asset = assetMapper.selectById(item.assetId());
            List<AssetEntity> superseded = assetMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AssetEntity>()
                            .eq(AssetEntity::getTenantId, asset.getTenantId())
                            .eq(AssetEntity::getProductId, asset.getProductId())
                            .eq(AssetEntity::getSpecCode, asset.getSpecCode())
                            .eq(AssetEntity::getVariant, asset.getVariant())
                            .eq(AssetEntity::getStatus, AssetStatus.PUBLISHED.name())
                            .ne(AssetEntity::getId, asset.getId()));
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
        publication.setArchivedAssetIds(objectMapper.writeValueAsString(archived));
    }

    // ---- failure restore ----

    private void restoreAndFail(PublicationEntity publication, CommercePublisher publisher,
            Throwable cause) {
        try {
            String beforeJson = publication.getBeforeJson();
            if (beforeJson != null) {
                WooProductSnapshot before = objectMapper.readValue(beforeJson,
                        WooProductSnapshot.class);
                publisher.updateContent(before.id(), ProductContentUpdate.restoring(before));
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
        // The APPLIED transaction may have rolled back after mutating this entity.
        publication.setStatus("FAILED");
        publication.setArchivedAssetIds("[]");
        publication.setPublishedAt(null);
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
            objectMapper.readTree(json).forEach((JsonNode node) -> ids.add(node.asLong()));
        }
        return ids;
    }

    private void update(PublicationEntity publication) {
        publicationMapper.updateById(publication);
    }
}
