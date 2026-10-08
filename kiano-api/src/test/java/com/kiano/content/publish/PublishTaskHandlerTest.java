package com.kiano.content.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kiano.commerce.CommercePublisher;
import com.kiano.commerce.ProductContentUpdate;
import com.kiano.commerce.ProductView;
import com.kiano.commerce.PublishEnvironment;
import com.kiano.commerce.WooMedia;
import com.kiano.commerce.WooProductSnapshot;
import com.kiano.commerce.WooProductSnapshot.RankMath;
import com.kiano.commerce.WooProductSnapshot.WooImageRef;
import com.kiano.commerce.CommercePublisherFactory;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.asset.ReviewService;
import com.kiano.content.asset.ReviewService.ReviewItem;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import com.kiano.platform.storage.ObjectStorage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * PublishTaskHandler with a programmable fake publisher: success paths for
 * staging/production, mid-publish failure restore (media deleted, nothing
 * changed), restore-failure needsAttention and product-not-found.
 */
class PublishTaskHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FakePublisher publisher = new FakePublisher();
    private PublicationEntity publication;
    private PublishTaskHandler handler;
    private long tenantId = 7L;
    private long productId = 21L;
    private long mainAssetId = 1L;
    private long longCopyId = 2L;

    @BeforeEach
    void setUp() {
        publication = new PublicationEntity();
        publication.setId(99L);
        publication.setTenantId(tenantId);
        publication.setProductId(productId);
        publication.setEnvironment(PublishEnvironment.STAGING.name());
        publication.setTarget("WOO_PRODUCT");
        // Main + 3 angles (the ordered gallery), plus the text assets.
        publication.setAssetIds(MAPPER.writeValueAsString(List.of(mainAssetId, longCopyId,
                5L, 6L, 7L, 3L, 4L)));
        publication.setUploadedMediaIds("[]");
        publication.setArchivedAssetIds("[]");
        publication.setStatus("PENDING");

        PublicationMapper publicationMapper = mock(PublicationMapper.class);
        when(publicationMapper.selectById(99L)).thenReturn(publication);
        when(publicationMapper.updateById(any(PublicationEntity.class)))
                .thenAnswer(inv -> 1);

        AssetMapper assetMapper = mock(AssetMapper.class);
        AssetEntity main = asset(mainAssetId, "PAGE_MAIN", "main", "IMAGE",
                "MG-BL200_page-main_real_1600x1600_v1.jpg");
        AssetEntity longCopy = asset(longCopyId, "COPY_LONG", "default", "TEXT", null);
        longCopy.setTextBody("<p>Long copy</p>");
        when(assetMapper.selectById(mainAssetId)).thenReturn(main);
        when(assetMapper.selectById(longCopyId)).thenReturn(longCopy);
        when(assetMapper.selectById(5L)).thenReturn(
                asset(5L, "PAGE_ANGLE", "P2", "IMAGE", "MG-BL200_page-a2_real_1600x1600_v1.jpg"));
        when(assetMapper.selectById(6L)).thenReturn(
                asset(6L, "PAGE_ANGLE", "P3", "IMAGE", "MG-BL200_page-a3_real_1600x1600_v1.jpg"));
        when(assetMapper.selectById(7L)).thenReturn(
                asset(7L, "PAGE_ANGLE", "P4", "IMAGE", "MG-BL200_page-a4_real_1600x1600_v1.jpg"));
        when(assetMapper.selectList(any())).thenReturn(new ArrayList<>());

        ReviewService reviewService = mock(ReviewService.class);
        when(reviewService.list(any(), any(), any(), any())).thenReturn(List.of(
                reviewItem(mainAssetId, "PAGE_MAIN", "main", "IMAGE", null),
                reviewItem(longCopyId, "COPY_LONG", "default", "TEXT", "<p>Long copy</p>"),
                reviewItem(3L, "COPY_TITLE", "default", "TEXT", "Morgan Kettle"),
                reviewItem(4L, "COPY_SHORT", "default", "TEXT", "<ul><li>x</li></ul>"),
                reviewItem(5L, "PAGE_ANGLE", "P2", "IMAGE", null),
                reviewItem(6L, "PAGE_ANGLE", "P3", "IMAGE", null),
                reviewItem(7L, "PAGE_ANGLE", "P4", "IMAGE", null)));

        ProductView product = new ProductView(productId, null, "simple", "MG-BL200",
                "Morgan Blender", null, null, null, null, null, "publish", null, List.of());
        com.kiano.commerce.ProductCatalog catalog = mock(com.kiano.commerce.ProductCatalog.class);
        when(catalog.findById(tenantId, productId)).thenReturn(Optional.of(product));

        CommercePublisherFactory factory = mock(CommercePublisherFactory.class);
        when(factory.forEnvironment(tenantId, PublishEnvironment.STAGING))
                .thenReturn(publisher);
        when(factory.forEnvironment(tenantId, PublishEnvironment.PRODUCTION))
                .thenReturn(publisher);

        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.download(any())).thenReturn(new byte[]{1, 2, 3});

        handler = new PublishTaskHandler(publicationMapper, assetMapper, storage,
                reviewService, catalog, factory, mock(AuditLog.class), MAPPER);
    }

    private static AssetEntity asset(long id, String spec, String variant, String kind,
            String fileName) {
        AssetEntity entity = new AssetEntity();
        entity.setId(id);
        entity.setTenantId(7L);
        entity.setProductId(21L);
        entity.setSpecCode(spec);
        entity.setVariant(variant);
        entity.setVersion(1);
        entity.setKind(kind);
        entity.setFileName(fileName);
        entity.setStatus(AssetStatus.APPROVED.name());
        entity.setObjectKey("t7/assets/" + spec + "/" + variant + "/v1.jpg");
        return entity;
    }

    private static ReviewItem reviewItem(long id, String spec, String variant, String kind,
            String textBody) {
        return new ReviewItem(id, 21L, "MG-BL200", "Morgan Blender", spec, variant, 1,
                "APPROVED", kind, List.of(), Map.of(), null, null, null, null, null, textBody,
                null, 1);
    }

    private TaskContext ctx() {
        return new TaskContext(1L, tenantId, MAPPER.createObjectNode().put("publicationId", 99L),
                1);
    }

    private void seedBeforeSnapshot() {
        WooProductSnapshot before = new WooProductSnapshot(500L, "MG-BL200", "Old name",
                "Old desc", "Old short",
                List.of(new WooImageRef(900L, "http://x/900.jpg", 0, "old")),
                new RankMath("Old SEO", "Old SEO desc"), Instant.parse("2026-01-01T00:00:00Z"));
        publication.setBeforeJson(MAPPER.writeValueAsString(before));
    }

    @Test
    void staging_success_appliedButAssetsNotPublished() throws Exception {
        seedBeforeSnapshot();
        publisher.productById = 500L;
        handler.handle(ctx());

        assertThat(publication.getStatus()).isEqualTo("APPLIED");
        assertThat(publication.getExternalRef()).isEqualTo("500");
        assertThat(publisher.updates).hasSize(1);
        assertThat(publisher.mediaUploads).isEqualTo(4); // main + 3 angles
        assertThat(publication.getAfterJson()).isNotBlank();
    }

    @Test
    void production_success_assetsPublished_previousArchived() throws Exception {
        publication.setEnvironment(PublishEnvironment.PRODUCTION.name());
        seedBeforeSnapshot();
        publisher.productById = 500L;
        handler.handle(ctx());

        assertThat(publication.getStatus()).isEqualTo("APPLIED");
        assertThat(publication.getArchivedAssetIds()).isNotBlank();
    }

    @Test
    void thirdUploadFails_restoresNothingChanged_deletesTwoUploaded_failed() throws Exception {
        seedBeforeSnapshot();
        publisher.productById = 500L;
        publisher.failOnUploadIndex = 3; // 1-based; third upload fails

        assertThatThrownBy(() -> handler.handle(ctx()))
                .isInstanceOf(NonRetryableTaskException.class);

        assertThat(publication.getStatus()).isEqualTo("FAILED");
        // The publish update never ran; the only update is the before-snapshot
        // restore (spec §10.1: restore name/descriptions/images/meta).
        assertThat(publisher.updates).hasSize(1);
        ProductContentUpdate restore = publisher.updates.get(0);
        assertThat(restore.name()).isEqualTo("Old name");
        assertThat(restore.imageIdsInOrder()).containsExactly(900L);
        // the two uploads that succeeded are deleted again
        assertThat(publisher.deletedMediaIds).hasSize(2);
        assertThat(publisher.mediaUploads).isEqualTo(3);
    }

    @Test
    void updateRejected_restoresBeforeSnapshotExactly_deletesAllUploaded() throws Exception {
        seedBeforeSnapshot();
        publisher.productById = 500L;
        publisher.failOnUpdate = true;

        assertThatThrownBy(() -> handler.handle(ctx()))
                .isInstanceOf(NonRetryableTaskException.class);

        assertThat(publication.getStatus()).isEqualTo("FAILED");
        assertThat(publisher.updates).hasSize(2); // original + restore
        ProductContentUpdate restore = publisher.updates.get(1);
        assertThat(restore.name()).isEqualTo("Old name");
        assertThat(restore.description()).isEqualTo("Old desc");
        assertThat(restore.shortDescription()).isEqualTo("Old short");
        assertThat(restore.imageIdsInOrder()).containsExactly(900L);
        assertThat(restore.seoTitle()).isEqualTo("Old SEO");
        assertThat(restore.seoDescription()).isEqualTo("Old SEO desc");
        assertThat(publisher.deletedMediaIds).hasSize(4);
    }

    @Test
    void restoreAlsoFails_needsAttention() throws Exception {
        seedBeforeSnapshot();
        publisher.productById = 500L;
        publisher.failOnUpdate = true;
        publisher.failRestore = true;

        assertThatThrownBy(() -> handler.handle(ctx()))
                .isInstanceOf(NonRetryableTaskException.class);

        assertThat(publication.getStatus()).isEqualTo("FAILED");
        assertThat(publication.getNeedsAttention()).isTrue();
        assertThat(publication.getError()).contains("restore failed");
    }

    @Test
    void productNotFoundBySku_failedWithCode() throws Exception {
        publication.setBeforeJson(null);
        publisher.productById = 0; // findBySku → empty

        assertThatThrownBy(() -> handler.handle(ctx()))
                .isInstanceOf(NonRetryableTaskException.class)
                .hasMessageContaining("WOO_PRODUCT_NOT_FOUND");
        assertThat(publication.getStatus()).isEqualTo("FAILED");
        assertThat(publication.getError()).contains("WOO_PRODUCT_NOT_FOUND");
    }

    /** Programmable fake publisher: records calls, can fail at chosen points. */
    private static class FakePublisher implements CommercePublisher {
        long productById = 500L;
        int failOnUploadIndex = -1;
        boolean failOnUpdate = false;
        boolean failRestore = false;
        int mediaUploads = 0;
        List<ProductContentUpdate> updates = new ArrayList<>();
        List<Long> deletedMediaIds = new ArrayList<>();

        @Override
        public Optional<WooProductSnapshot> findBySku(String sku) {
            if (productById == 0) {
                return Optional.empty();
            }
            return Optional.of(new WooProductSnapshot(productById, sku, "Old name", "Old desc",
                    "Old short", List.of(new WooImageRef(900L, "http://x/900.jpg", 0, "old")),
                    new RankMath("Old SEO", "Old SEO desc"),
                    Instant.parse("2026-01-01T00:00:00Z")));
        }

        @Override
        public WooProductSnapshot get(long productId) {
            return new WooProductSnapshot(productId, "MG-BL200", "Old name", "Old desc",
                    "Old short", List.of(new WooImageRef(900L, "http://x/900.jpg", 0, "old")),
                    new RankMath("Old SEO", "Old SEO desc"),
                    Instant.parse("2026-01-01T00:00:00Z"));
        }

        @Override
        public WooMedia uploadMedia(String fileName, byte[] bytes, String contentType,
                String altText) {
            mediaUploads++;
            if (mediaUploads == failOnUploadIndex) {
                throw new IllegalStateException("upload 500");
            }
            return new WooMedia(1000L + mediaUploads, "http://x/" + (1000L + mediaUploads));
        }

        @Override
        public WooProductSnapshot updateContent(long productId, ProductContentUpdate update) {
            updates.add(update);
            if (failOnUpdate && updates.size() == 1) {
                throw new IllegalStateException("put 400");
            }
            if (failRestore && updates.size() == 2) {
                throw new IllegalStateException("restore 500");
            }
            return new WooProductSnapshot(productId, "MG-BL200", update.name(),
                    update.description(), update.shortDescription(),
                    update.imageIdsInOrder().stream()
                            .map(id -> new WooImageRef(id, "http://x/" + id, 0, "a"))
                            .toList(),
                    new RankMath(update.seoTitle(), update.seoDescription()),
                    Instant.parse("2026-01-01T00:00:00Z"));
        }

        @Override
        public void deleteMedia(long mediaId) {
            deletedMediaIds.add(mediaId);
        }
    }
}