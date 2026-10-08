package com.kiano.content.publish;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.CommercePublisher;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductContentUpdate;
import com.kiano.commerce.ProductView;
import com.kiano.commerce.PublishEnvironment;
import com.kiano.commerce.WooProductSnapshot;
import com.kiano.commerce.woo.CommercePublisherFactory;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.asset.ReviewService.ReviewItem;
import com.kiano.content.asset.ReviewService;
import com.kiano.content.policy.PolicyService;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.auth.AppUserEntity;
import com.kiano.platform.auth.AppUserMapper;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.queue.TaskQueue;
import com.kiano.platform.web.ApiException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-SKU publishing (spec §10.1): request() validates preconditions in
 * order, inserts a PENDING publication and enqueues PUBLISH_PRODUCT;
 * rollback() (Task 11) restores the before snapshot. PRODUCTION requires a
 * matching APPLIED staging publication (STAGING_REQUIRED gate).
 */
@Service
public class PublicationService {

    private static final Set<String> REQUIRED_SPECS = Set.of("PAGE_MAIN", "COPY_TITLE",
            "COPY_SHORT", "COPY_LONG");
    private static final int MIN_ANGLES = 3;
    private static final String OPTIONAL_SEO = "COPY_SEO";

    private final PublicationMapper mapper;
    private final com.kiano.content.asset.AssetMapper assetMapper;
    private final ReviewService reviewService;
    private final PolicyService policyService;
    private final ProductCatalog productCatalog;
    private final AppUserMapper appUserMapper;
    private final TaskQueue queue;
    private final CommercePublisherFactory publisherFactory;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;

    public PublicationService(PublicationMapper mapper,
            com.kiano.content.asset.AssetMapper assetMapper, ReviewService reviewService,
            PolicyService policyService, ProductCatalog productCatalog,
            AppUserMapper appUserMapper, TaskQueue queue,
            CommercePublisherFactory publisherFactory, AuditLog auditLog,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.assetMapper = assetMapper;
        this.reviewService = reviewService;
        this.policyService = policyService;
        this.productCatalog = productCatalog;
        this.appUserMapper = appUserMapper;
        this.queue = queue;
        this.publisherFactory = publisherFactory;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public long request(CurrentUser user, long productId, PublishEnvironment environment) {
        requireRole(user, environment);
        productCatalog.findById(user.tenantId(), productId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Product not found"));
        List<ReviewItem> approved = reviewService.list(user, productId, "APPROVED", null);
        List<String> missing = missingRequired(approved);
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PUBLISH_PRECONDITIONS",
                    "Not all required assets are approved",
                    Map.of("missing", missing));
        }
        int policyVersion = policyService.requireComplete(user.tenantId()).version();
        ReviewItem longCopy = approved.stream()
                .filter(item -> "COPY_LONG".equals(item.specCode())).findFirst().get();
        Integer longCopyPolicy = readContentPolicyVersion(longCopy);
        if (longCopyPolicy == null || longCopyPolicy != policyVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "COPY_POLICY_OUTDATED",
                    "COPY_LONG was rendered against an older policy; regenerate it before publishing",
                    Map.of("expectedPolicy", policyVersion, "copyPolicy", longCopyPolicy));
        }
        List<Long> assetIds = publishableAssetIds(approved);
        if (environment == PublishEnvironment.PRODUCTION) {
            verifyStagingGate(user.tenantId(), productId, assetIds);
        }
        PublicationEntity entity = new PublicationEntity();
        entity.setTenantId(user.tenantId());
        entity.setProductId(productId);
        entity.setEnvironment(environment.name());
        entity.setTarget("WOO_PRODUCT");
        entity.setAssetIds(objectMapper.writeValueAsString(assetIds));
        entity.setUploadedMediaIds("[]");
        entity.setArchivedAssetIds("[]");
        entity.setStatus("PENDING");
        entity.setNeedsAttention(false);
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        mapper.insert(entity);
        Optional<Long> taskId = queue.enqueue(user.tenantId(), PublishTaskHandler.TYPE,
                Map.of("publicationId", entity.getId()),
                "publish:" + productId + ":" + environment.name());
        if (taskId.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PUBLISH_IN_PROGRESS",
                    "A publish for this product and environment is already queued or running");
        }
        auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                String.valueOf(user.userId()), "PUBLISH_REQUESTED", "product",
                String.valueOf(productId),
                Map.of("environment", environment.name(), "assetIds", assetIds),
                null, null, "PUBLISH"));
        return entity.getId();
    }

    /** Latest APPLIED publication of this product/env and its id set. */
    public Optional<PublicationEntity> latestApplied(long tenantId, long productId,
            PublishEnvironment environment) {
        List<PublicationEntity> rows = mapper.selectList(Wrappers.<PublicationEntity>lambdaQuery()
                .eq(PublicationEntity::getTenantId, tenantId)
                .eq(PublicationEntity::getProductId, productId)
                .eq(PublicationEntity::getEnvironment, environment.name())
                .eq(PublicationEntity::getStatus, "APPLIED")
                .orderByDesc(PublicationEntity::getId)
                .last("limit 1"));
        return rows.stream().findFirst();
    }

    public List<PublicationView> list(CurrentUser user, long productId) {
        List<PublicationEntity> rows = mapper.selectList(Wrappers.<PublicationEntity>lambdaQuery()
                .eq(PublicationEntity::getTenantId, user.tenantId())
                .eq(PublicationEntity::getProductId, productId)
                .orderByDesc(PublicationEntity::getId));
        List<PublicationView> views = new ArrayList<>();
        for (PublicationEntity row : rows) {
            views.add(toView(user, row));
        }
        return views;
    }

    /**
     * Rollback (Task 11): restores the before snapshot on Woo, deletes the
     * uploaded media and, for PRODUCTION, reverts asset statuses. Guarded by
     * the latest-APPLIED rule and Woo change detection (WOO_CHANGED_SINCE_
     * PUBLISH unless force=true). Synchronous - one PUT plus a few DELETEs.
     */
    public PublicationView rollback(CurrentUser user, long publicationId, boolean force) {
        PublicationEntity publication = mapper.selectOne(Wrappers.<PublicationEntity>lambdaQuery()
                .eq(PublicationEntity::getTenantId, user.tenantId())
                .eq(PublicationEntity::getId, publicationId));
        if (publication == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Publication not found");
        }
        PublishEnvironment environment = PublishEnvironment.valueOf(publication.getEnvironment());
        requireRole(user, environment);
        Optional<PublicationEntity> latest = latestApplied(user.tenantId(),
                publication.getProductId(), environment);
        if (latest.isEmpty() || latest.get().getId() != publication.getId()
                || !"APPLIED".equals(publication.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "NOT_LATEST_PUBLICATION",
                    "Only the latest APPLIED publication can be rolled back");
        }
        CommercePublisher publisher = publisherFactory.forEnvironment(user.tenantId(), environment);
        WooProductSnapshot current = publisher.get(publication.getExternalRef() == null ? 0L
                : Long.parseLong(publication.getExternalRef()));
        WooProductSnapshot after = objectMapper.readValue(publication.getAfterJson(),
                WooProductSnapshot.class);
        boolean changed = after.modifiedAt() != null
                && !after.modifiedAt().equals(current.modifiedAt());
        if (changed && !force) {
            throw new ApiException(HttpStatus.CONFLICT, "WOO_CHANGED_SINCE_PUBLISH",
                    "The product was modified in Woo after this publish; force to overwrite",
                    Map.of("publishedModifiedAt", String.valueOf(after.modifiedAt()),
                            "currentModifiedAt", String.valueOf(current.modifiedAt())));
        }
        WooProductSnapshot before = objectMapper.readValue(publication.getBeforeJson(),
                WooProductSnapshot.class);
        publisher.updateContent(current.id(), new ProductContentUpdate(before.name(),
                before.description(), before.shortDescription(),
                before.images().stream().map(WooProductSnapshot.WooImageRef::id).toList(),
                before.rankMath() == null ? null : before.rankMath().title(),
                before.rankMath() == null ? null : before.rankMath().description()));
        for (Long mediaId : readIdList(publication.getUploadedMediaIds())) {
            try {
                publisher.deleteMedia(mediaId);
            } catch (RuntimeException ex) {
                // a single media delete failure never aborts the rollback
            }
        }
        publication.setStatus("ROLLED_BACK");
        publication.setNeedsAttention(false);
        if (environment == PublishEnvironment.PRODUCTION) {
            revertAssetStatuses(publication);
        }
        mapper.updateById(publication);
        auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                String.valueOf(user.userId()), "PUBLICATION_ROLLED_BACK", "publication",
                String.valueOf(publication.getId()),
                Map.of("environment", environment.name(), "force", force), null, null, "PUBLISH"));
        return toView(user, publication);
    }

    /** PRODUCTION rollback: published → approved; archived-during-publish → published. */
    @Transactional
    protected void revertAssetStatuses(PublicationEntity publication) {
        for (Long assetId : readIdList(publication.getAssetIds())) {
            com.kiano.content.asset.AssetEntity asset = assetMapper.selectById(assetId);
            if (asset != null && AssetStatus.PUBLISHED.name().equals(asset.getStatus())) {
                asset.setStatus(AssetStatus.APPROVED.name());
                assetMapper.updateById(asset);
            }
        }
        for (Long assetId : readIdList(publication.getArchivedAssetIds())) {
            com.kiano.content.asset.AssetEntity asset = assetMapper.selectById(assetId);
            if (asset != null && AssetStatus.ARCHIVED.name().equals(asset.getStatus())) {
                asset.setStatus(AssetStatus.PUBLISHED.name());
                assetMapper.updateById(asset);
            }
        }
    }

    /** The exact asset-id set a publish writes: gallery + title/short/long + SEO. */
    private List<Long> publishableAssetIds(List<ReviewItem> approved) {
        List<Long> ids = new ArrayList<>();
        for (ReviewItem item : GallerySelector.select(approved)) {
            ids.add(item.assetId());
        }
        for (ReviewItem item : approved) {
            if (Set.of("COPY_TITLE", "COPY_SHORT", "COPY_LONG", OPTIONAL_SEO)
                    .contains(item.specCode())) {
                ids.add(item.assetId());
            }
        }
        return ids;
    }

    private List<String> missingRequired(List<ReviewItem> approved) {
        List<String> missing = new ArrayList<>();
        for (String spec : REQUIRED_SPECS) {
            boolean present = approved.stream()
                    .anyMatch(item -> spec.equals(item.specCode()));
            if (!present) {
                missing.add(spec);
            }
        }
        long angles = approved.stream()
                .filter(item -> "PAGE_ANGLE".equals(item.specCode())).count();
        if (angles < MIN_ANGLES) {
            missing.add("PAGE_ANGLE x" + MIN_ANGLES + " (have " + angles + ")");
        }
        return missing;
    }

    private void verifyStagingGate(long tenantId, long productId, List<Long> assetIds) {
        Optional<PublicationEntity> staging = latestApplied(tenantId, productId,
                PublishEnvironment.STAGING);
        if (staging.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "STAGING_REQUIRED",
                    "This product has not been published to staging yet",
                    Map.of("diff", Map.of("added", assetIds, "removed", List.of())));
        }
        Set<Long> stagedIds = new TreeSet<>(readIdList(staging.get().getAssetIds()));
        Set<Long> currentIds = new TreeSet<>(assetIds);
        if (!stagedIds.equals(currentIds)) {
            List<Long> added = currentIds.stream().filter(id -> !stagedIds.contains(id)).toList();
            List<Long> removed = stagedIds.stream().filter(id -> !currentIds.contains(id)).toList();
            throw new ApiException(HttpStatus.CONFLICT, "STAGING_REQUIRED",
                    "The asset set changed since the staging publish; publish to staging again",
                    Map.of("diff", Map.of("added", added, "removed", removed)));
        }
    }

    /** policyVersion stored in COPY_LONG's content_json (null when never set). */
    private @Nullable Integer readContentPolicyVersion(ReviewItem item) {
        com.kiano.content.asset.AssetEntity asset = assetMapper.selectById(item.assetId());
        if (asset == null || asset.getContentJson() == null) {
            return null;
        }
        JsonNode content = objectMapper.readTree(asset.getContentJson());
        JsonNode policyVersion = content.path("policyVersion");
        return policyVersion.isNull() || policyVersion.isMissingNode() ? null
                : policyVersion.asInt();
    }

    private List<Long> readIdList(String json) {
        List<Long> ids = new ArrayList<>();
        objectMapper.readTree(json).forEach(node -> ids.add(node.asLong()));
        return ids;
    }

    private PublicationView toView(CurrentUser user, PublicationEntity row) {
        ProductView product = productCatalog.findById(row.getTenantId(), row.getProductId())
                .orElse(null);
        String publishedBy = null;
        if (row.getPublishedBy() != null) {
            AppUserEntity author = appUserMapper.selectById(row.getPublishedBy());
            publishedBy = author == null ? null : author.getEmail();
        }
        boolean canRollback = "APPLIED".equals(row.getStatus())
                && latestApplied(row.getTenantId(), row.getProductId(),
                        PublishEnvironment.valueOf(row.getEnvironment()))
                        .map(p -> p.getId() == row.getId()).orElse(false)
                && canAct(row, user);
        return new PublicationView(row.getId(), row.getProductId(),
                product == null ? null : product.sku(),
                product == null ? null : product.name(),
                row.getEnvironment(), row.getStatus(), Boolean.TRUE.equals(
                        row.getNeedsAttention()), row.getError(), readIdList(row.getAssetIds()),
                row.getPublishedAt() == null ? null : row.getPublishedAt().toInstant(),
                publishedBy, canRollback);
    }

    private static boolean canAct(PublicationEntity row, CurrentUser user) {
        boolean production = "PRODUCTION".equals(row.getEnvironment());
        return production ? user.role() == Role.OWNER : user.role() == Role.OPERATOR
                || user.role() == Role.OWNER;
    }

    private static void requireRole(CurrentUser user, PublishEnvironment environment) {
        boolean production = environment == PublishEnvironment.PRODUCTION;
        if (production && user.role() != Role.OWNER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Publishing to production requires the OWNER role");
        }
    }
}