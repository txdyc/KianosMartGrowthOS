package com.kiano.content.batch;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.ContentTier;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.profile.ProductProfileService;
import com.kiano.content.shots.ContentProductSummary;
import com.kiano.content.shots.ShotStatusService;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.web.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch lifecycle (C6 Task 2): creating a batch of STANDARD SKUs (HERO and
 * variations are rejected up front), pausing, resuming and cancelling it.
 * Cancelling only cancels the batch's jobs that no worker has leased yet;
 * everything already produced stays.
 */
@Service
public class BatchService {

    /** One batch as shown in the list and the dashboard header. */
    public record BatchView(long id, String name, String status, boolean nightOnly, int itemCount,
            OffsetDateTime createdAt, @Nullable OffsetDateTime finishedAt) {
    }

    /** One selectable SKU of the creation page. */
    public record Candidate(long productId, String sku, String name, boolean shotsComplete,
            @Nullable Long activeBatchId) {
    }

    private final BatchMapper batchMapper;
    private final BatchItemMapper itemMapper;
    private final ProductCatalog productCatalog;
    private final ProductProfileService profileService;
    private final ShotStatusService shotStatusService;
    private final GenerationJobStore jobs;
    private final AuditLog auditLog;
    private final JdbcTemplate jdbcTemplate;

    public BatchService(BatchMapper batchMapper, BatchItemMapper itemMapper,
            ProductCatalog productCatalog, ProductProfileService profileService,
            ShotStatusService shotStatusService, GenerationJobStore jobs, AuditLog auditLog,
            JdbcTemplate jdbcTemplate) {
        this.batchMapper = batchMapper;
        this.itemMapper = itemMapper;
        this.productCatalog = productCatalog;
        this.profileService = profileService;
        this.shotStatusService = shotStatusService;
        this.jobs = jobs;
        this.auditLog = auditLog;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public BatchView create(CurrentUser user, String name, List<Long> productIds, boolean nightOnly) {
        long tenantId = user.tenantId();
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "A batch needs a name");
        }
        if (productIds == null || productIds.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "Select at least one product", Map.of("productIds", List.of()));
        }
        Set<Long> seen = new LinkedHashSet<>();
        Set<Long> duplicates = new LinkedHashSet<>();
        for (Long productId : productIds) {
            if (!seen.add(productId)) {
                duplicates.add(productId);
            }
        }
        if (!duplicates.isEmpty()) {
            // the unique (batch_id, product_id) key would otherwise surface as a 500
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "Each product can be selected only once",
                    Map.of("duplicates", List.copyOf(duplicates)));
        }
        List<Long> notTopLevel = new ArrayList<>();
        List<Long> notStandard = new ArrayList<>();
        for (long productId : productIds) {
            ProductView product = productCatalog.findById(tenantId, productId).orElse(null);
            if (product == null || product.parentId() != null) {
                notTopLevel.add(productId);
                continue;
            }
            if (profileService.tierOf(tenantId, productId) != ContentTier.STANDARD) {
                notStandard.add(productId);
            }
        }
        if (!notTopLevel.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_TOP_LEVEL",
                    "Only top-level products can join a batch",
                    Map.of("productIds", notTopLevel));
        }
        if (!notStandard.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BATCH_HERO_NOT_ALLOWED",
                    "Batches carry STANDARD products only; HERO products are processed on their own",
                    Map.of("productIds", notStandard));
        }
        // two concurrent creates of the tenant are serialized: the second one
        // sees the first one's items and gets PRODUCT_IN_ACTIVE_BATCH
        jdbcTemplate.queryForObject("select pg_advisory_xact_lock(hashtext(?))::text",
                String.class, "kiano-batch-create:" + tenantId);
        Map<Long, Long> activeBatches = activeBatchByProduct(tenantId);
        List<Long> inBatch = productIds.stream()
                .filter(activeBatches::containsKey).toList();
        if (!inBatch.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_IN_ACTIVE_BATCH",
                    "These products are already in a running or paused batch",
                    Map.of("productIds", inBatch));
        }

        BatchEntity batch = new BatchEntity();
        batch.setTenantId(tenantId);
        batch.setName(name.trim());
        batch.setStatus("RUNNING");
        batch.setNightOnly(nightOnly);
        batch.setCreatedBy(user.userId());
        batch.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        batchMapper.insert(batch);

        int position = 0;
        for (long productId : productIds) {
            BatchItemEntity item = new BatchItemEntity();
            item.setTenantId(tenantId);
            item.setBatchId(batch.getId());
            item.setProductId(productId);
            item.setPosition(position++);
            item.setAddedAt(OffsetDateTime.now(ZoneOffset.UTC));
            itemMapper.insert(item);
        }
        auditLog.record(new AuditEntry(tenantId, ActorType.USER, String.valueOf(user.userId()),
                "BATCH_CREATED", "batch", String.valueOf(batch.getId()), null,
                Map.of("name", batch.getName(), "productIds", productIds, "nightOnly", nightOnly),
                null, "BATCH"));
        return view(batch, productIds.size());
    }

    /** Every batch of the tenant, newest first. */
    public List<BatchView> list(long tenantId) {
        List<BatchView> views = new ArrayList<>();
        for (BatchEntity batch : batchMapper.selectList(Wrappers.<BatchEntity>lambdaQuery()
                .eq(BatchEntity::getTenantId, tenantId)
                .orderByDesc(BatchEntity::getId))) {
            views.add(view(batch, itemCount(tenantId, batch.getId())));
        }
        return views;
    }

    /** One batch of the tenant, or null. */
    public @Nullable BatchEntity find(long tenantId, long batchId) {
        return batchMapper.selectOne(Wrappers.<BatchEntity>lambdaQuery()
                .eq(BatchEntity::getTenantId, tenantId)
                .eq(BatchEntity::getId, batchId));
    }

    /** The batch's items ordered by their position in the creation form. */
    public List<BatchItemEntity> items(long tenantId, long batchId) {
        return itemMapper.selectList(Wrappers.<BatchItemEntity>lambdaQuery()
                .eq(BatchItemEntity::getTenantId, tenantId)
                .eq(BatchItemEntity::getBatchId, batchId)
                .orderByAsc(BatchItemEntity::getPosition));
    }

    @Transactional
    public BatchView pause(CurrentUser user, long batchId) {
        return transition(user, batchId, "RUNNING", "PAUSED", "BATCH_PAUSED");
    }

    @Transactional
    public BatchView resume(CurrentUser user, long batchId) {
        return transition(user, batchId, "PAUSED", "RUNNING", "BATCH_RESUMED");
    }

    /** Cancels the batch and its not-yet-leased jobs; finished work is kept. */
    @Transactional
    public BatchView cancel(CurrentUser user, long batchId) {
        long tenantId = user.tenantId();
        BatchEntity batch = batchMapper.selectOne(Wrappers.<BatchEntity>lambdaQuery()
                .eq(BatchEntity::getTenantId, tenantId)
                .eq(BatchEntity::getId, batchId));
        if (batch == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Batch not found");
        }
        if (!List.of("RUNNING", "PAUSED").contains(batch.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "BATCH_STATE",
                    "Batch " + batchId + " is " + batch.getStatus());
        }
        int cancelled = jobs.cancelBatchJobs(tenantId, batchId, "batch cancelled");
        batch.setStatus("CANCELLED");
        batch.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        batchMapper.updateById(batch);
        auditLog.record(new AuditEntry(tenantId, ActorType.USER, String.valueOf(user.userId()),
                "BATCH_CANCELLED", "batch", String.valueOf(batchId), null,
                Map.of("cancelledJobs", cancelled), null, "BATCH"));
        return view(batch, itemCount(tenantId, batchId));
    }

    /** The STANDARD top-level SKUs of the creation page, with their shot state. */
    public List<Candidate> candidates(long tenantId) {
        Map<Long, Long> activeBatches = activeBatchByProduct(tenantId);
        List<Candidate> candidates = new ArrayList<>();
        for (ContentProductSummary summary : shotStatusService.summaries(tenantId,
                ContentTier.STANDARD, null)) {
            candidates.add(new Candidate(summary.productId(), summary.sku(), summary.name(),
                    summary.complete(), activeBatches.get(summary.productId())));
        }
        return candidates;
    }

    /** The RUNNING or PAUSED batch each product currently belongs to. */
    private Map<Long, Long> activeBatchByProduct(long tenantId) {
        Map<Long, Long> byProduct = new LinkedHashMap<>();
        for (BatchItemEntity item : itemMapper.selectList(Wrappers.<BatchItemEntity>lambdaQuery()
                .eq(BatchItemEntity::getTenantId, tenantId))) {
            BatchEntity batch = find(tenantId, item.getBatchId());
            if (batch != null && List.of("RUNNING", "PAUSED").contains(batch.getStatus())) {
                byProduct.putIfAbsent(item.getProductId(), batch.getId());
            }
        }
        return byProduct;
    }

    private BatchView transition(CurrentUser user, long batchId, String from, String to,
            String action) {
        long tenantId = user.tenantId();
        BatchEntity batch = batchMapper.selectOne(Wrappers.<BatchEntity>lambdaQuery()
                .eq(BatchEntity::getTenantId, tenantId)
                .eq(BatchEntity::getId, batchId));
        if (batch == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Batch not found");
        }
        if (!from.equals(batch.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "BATCH_STATE",
                    "Batch " + batchId + " is " + batch.getStatus() + ", not " + from);
        }
        batch.setStatus(to);
        batchMapper.updateById(batch);
        auditLog.record(new AuditEntry(tenantId, ActorType.USER, String.valueOf(user.userId()),
                action, "batch", String.valueOf(batchId), Map.of("status", from),
                Map.of("status", to), null, "BATCH"));
        return view(batch, itemCount(tenantId, batchId));
    }

    private int itemCount(long tenantId, long batchId) {
        return Math.toIntExact(itemMapper.selectCount(Wrappers.<BatchItemEntity>lambdaQuery()
                .eq(BatchItemEntity::getTenantId, tenantId)
                .eq(BatchItemEntity::getBatchId, batchId)));
    }

    private static BatchView view(BatchEntity batch, int itemCount) {
        return new BatchView(batch.getId(), batch.getName(), batch.getStatus(),
                Boolean.TRUE.equals(batch.getNightOnly()), itemCount, batch.getCreatedAt(),
                batch.getFinishedAt());
    }
}