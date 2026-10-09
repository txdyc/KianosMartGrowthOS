package com.kiano.content.publish;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.CommercePublisher;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductContentUpdate;
import com.kiano.commerce.ProductView;
import com.kiano.commerce.PublishEnvironment;
import com.kiano.commerce.WooProductSnapshot;
import com.kiano.commerce.CommercePublisherFactory;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.asset.ReviewService.ReviewItem;
import com.kiano.content.asset.ReviewService;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactSheetService.FactSheetView;
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
import java.util.LinkedHashSet;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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

    private static final String OPTIONAL_SEO = "COPY_SEO";

    private final PublicationMapper mapper;
    private final com.kiano.content.asset.AssetMapper assetMapper;
    private final ReviewService reviewService;
    private final PolicyService policyService;
    private final FactSheetService factSheetService;
    private final ProductCatalog productCatalog;
    private final AppUserMapper appUserMapper;
    private final TaskQueue queue;
    private final CommercePublisherFactory publisherFactory;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public PublicationService(PublicationMapper mapper,
            com.kiano.content.asset.AssetMapper assetMapper, ReviewService reviewService,
            PolicyService policyService, FactSheetService factSheetService,
            ProductCatalog productCatalog, AppUserMapper appUserMapper, TaskQueue queue,
            CommercePublisherFactory publisherFactory, AuditLog auditLog,
            ObjectMapper objectMapper, TransactionTemplate transactionTemplate) {
        this.mapper = mapper;
        this.assetMapper = assetMapper;
        this.reviewService = reviewService;
        this.policyService = policyService;
        this.factSheetService = factSheetService;
        this.productCatalog = productCatalog;
        this.appUserMapper = appUserMapper;
        this.queue = queue;
        this.publisherFactory = publisherFactory;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional
    public long request(CurrentUser user, long productId, PublishEnvironment environment) {
        requireRole(user, environment);
        return enqueue(user.tenantId(), productId, environment, user.userId(), ActorType.USER,
                String.valueOf(user.userId()), "PUBLISH", 0, null, null);
    }

    /**
     * SYSTEM staging publish for a batch (C6 Task 3): the same preconditions as
     * {@link #request}, but the publication has no publisher, the audit is
     * SYSTEM/BATCH and the task carries the batch priority. {@code runAfter}
     * spaces the publishes of one batch (the orchestrator passes the next slot).
     */
    @Transactional
    public long requestSystem(long tenantId, long productId, long batchId,
            @Nullable Instant runAfter) {
        return enqueue(tenantId, productId, PublishEnvironment.STAGING, null, ActorType.SYSTEM,
                "SYSTEM", "BATCH", 10, runAfter, Map.of("batchId", batchId));
    }

    /**
     * OWNER production publish spaced by the batch publish interval (C6
     * Task 6): same preconditions and staging gate as {@link #request}, but the
     * task carries the batch priority and the caller's {@code runAfter}.
     */
    @Transactional
    public long requestProductionSpaced(CurrentUser user, long productId,
            @Nullable Instant runAfter) {
        requireRole(user, PublishEnvironment.PRODUCTION);
        return enqueue(user.tenantId(), productId, PublishEnvironment.PRODUCTION, user.userId(),
                ActorType.USER, String.valueOf(user.userId()), "PUBLISH", 10, runAfter, null);
    }

    /** Shared publish request: preconditions, row, task and audit. */
    private long enqueue(long tenantId, long productId, PublishEnvironment environment,
            @Nullable Long publishedBy, ActorType actor, String actorId, String source,
            int priority, @Nullable Instant runAfter, @Nullable Map<String, Object> extraAfter) {
        productCatalog.findById(tenantId, productId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Product not found"));
        List<ReviewItem> approved = PublishRules.liveContent(
                reviewService.list(tenantId, productId));
        List<String> missing = PublishRules.missingRequired(approved);
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PUBLISH_PRECONDITIONS",
                    "Not all required assets are approved",
                    Map.of("missing", missing));
        }
        int policyVersion = policyService.requireComplete(tenantId).version();
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
            verifyStagingGate(tenantId, productId, assetIds);
        }
        PublicationEntity entity = new PublicationEntity();
        entity.setTenantId(tenantId);
        entity.setProductId(productId);
        entity.setEnvironment(environment.name());
        entity.setTarget("WOO_PRODUCT");
        entity.setAssetIds(objectMapper.writeValueAsString(assetIds));
        entity.setUploadedMediaIds("[]");
        entity.setArchivedAssetIds("[]");
        entity.setStatus("PENDING");
        entity.setNeedsAttention(false);
        entity.setPublishedBy(publishedBy);
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        mapper.insert(entity);
        Optional<Long> taskId = queue.enqueue(tenantId, PublishTaskHandler.TYPE,
                Map.of("publicationId", entity.getId()),
                "publish:" + productId + ":" + environment.name(), priority, runAfter);
        if (taskId.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PUBLISH_IN_PROGRESS",
                    "A publish for this product and environment is already queued or running");
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("environment", environment.name());
        after.put("assetIds", assetIds);
        if (extraAfter != null) {
            after.putAll(extraAfter);
        }
        auditLog.record(new AuditEntry(tenantId, actor, actorId, "PUBLISH_REQUESTED", "product",
                String.valueOf(productId), null, after, null, source));
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
     * Products whose current production content is out of date (Task 12):
     * an asset that was PUBLISHED now has a STALE version because the facts
     * or the store policy changed after the last publish. Reasons are
     * FACTS_CHANGED (a stale asset carries an older fact version than the
     * currently locked sheet) and/or POLICY_CHANGED (a stale COPY_LONG was
     * rendered against an older policy version).
     */
    public List<NeedsRepublishItem> needsRepublish(long tenantId) {
        List<com.kiano.content.asset.AssetEntity> assets = new ArrayList<>(
                assetMapper.selectList(Wrappers.<com.kiano.content.asset.AssetEntity>lambdaQuery()
                        .eq(com.kiano.content.asset.AssetEntity::getTenantId, tenantId)
                        .eq(com.kiano.content.asset.AssetEntity::getStatus, AssetStatus.STALE.name())));
        if (assets.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<Long, NeedsRepublishItem> byProduct = new LinkedHashMap<>();
        for (com.kiano.content.asset.AssetEntity asset : assets) {
            NeedsRepublishItem item = byProduct.computeIfAbsent(asset.getProductId(),
                    id -> {
                        ProductView product = productCatalog.findById(tenantId, id).orElse(null);
                        return new NeedsRepublishItem(id,
                                product == null ? null : product.sku(),
                                product == null ? null : product.name(),
                                new LinkedHashSet<>());
                    });
            if ("COPY_LONG".equals(asset.getSpecCode())) {
                Integer contentPolicy = readContentPolicyVersion(asset);
                PolicyService.PolicyView current = policyService.current(tenantId).orElse(null);
                if (current != null && contentPolicy != null && contentPolicy != current.version()) {
                    item.reasons().add("POLICY_CHANGED");
                }
            }
            FactSheetView locked = factSheetService.locked(tenantId, asset.getProductId())
                    .orElse(null);
            if (locked != null && asset.getFactVersion() != null
                    && asset.getFactVersion() < locked.version()) {
                item.reasons().add("FACTS_CHANGED");
            }
        }
        return byProduct.values().stream()
                .filter(item -> !item.reasons().isEmpty())
                .map(item -> new NeedsRepublishItem(item.productId(), item.sku(),
                        item.productName(), sortedReasons(item.reasons())))
                .toList();
    }

    private static LinkedHashSet<String> sortedReasons(LinkedHashSet<String> reasons) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String reason : List.of("FACTS_CHANGED", "POLICY_CHANGED")) {
            if (reasons.contains(reason)) {
                out.add(reason);
            }
        }
        return out;
    }

    /** One product that was published but whose content is now out of date. */
    public record NeedsRepublishItem(long productId, @Nullable String sku,
            @Nullable String productName, LinkedHashSet<String> reasons) {
    }

    private @Nullable Integer readContentPolicyVersion(
            com.kiano.content.asset.AssetEntity asset) {
        if (asset.getContentJson() == null) {
            return null;
        }
        JsonNode content = objectMapper.readTree(asset.getContentJson());
        JsonNode version = content.path("policyVersion");
        return version.isNull() || version.isMissingNode() ? null : version.asInt();
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
        if (latest.isEmpty() || !latest.get().getId().equals(publication.getId())
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
        publisher.updateContent(current.id(), ProductContentUpdate.restoring(before));
        for (Long mediaId : readIdList(publication.getUploadedMediaIds())) {
            try {
                publisher.deleteMedia(mediaId);
            } catch (RuntimeException ex) {
                // a single media delete failure never aborts the rollback
            }
        }
        // Status reversal, the publication row and its audit entry commit together.
        transactionTemplate.executeWithoutResult(tx -> {
            publication.setStatus("ROLLED_BACK");
            publication.setNeedsAttention(false);
            if (environment == PublishEnvironment.PRODUCTION) {
                revertAssetStatuses(publication);
            }
            mapper.updateById(publication);
            auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                    String.valueOf(user.userId()), "PUBLICATION_ROLLED_BACK", "publication",
                    String.valueOf(publication.getId()),
                    Map.of("environment", environment.name(), "force", force), null, null,
                    "PUBLISH"));
        });
        return toView(user, publication);
    }

    /** PRODUCTION rollback: published → approved; archived-during-publish → published. */
    private void revertAssetStatuses(PublicationEntity publication) {
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
                        .map(p -> p.getId().equals(row.getId())).orElse(false)
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