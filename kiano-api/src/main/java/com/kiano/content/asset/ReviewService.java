package com.kiano.content.asset;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.content.media.SourceMediaEntity;
import com.kiano.content.media.SourceMediaMapper;
import com.kiano.content.workflow.WorkflowRegistry;
import com.kiano.content.workflow.WorkflowRegistry.ComfyWorkflowView;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.JobStep;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The review board: lists assets with presigned URLs (flagged first, then
 * sku, spec order MAIN → ANGLE → SCENE → INBOX, variant), records approve/
 * reject decisions with reasons, recreates the generating job on REGENERATE
 * and bulk-approves what is left of a product.
 */
@Service
public class ReviewService {

    private static final Duration URL_TTL = Duration.ofMinutes(15);
    private static final Map<String, Integer> SPEC_ORDER = Map.of(
            "PAGE_MAIN", 0, "PAGE_ANGLE", 1, "PAGE_SCENE", 2, "PAGE_INBOX", 3,
            "PAGE_INFO", 4, "PAGE_SPEC", 5);
    private static final Comparator<ReviewItem> ORDER = Comparator
            .comparing((ReviewItem item) -> item.flags().isEmpty())
            .thenComparing(ReviewItem::sku, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(item -> SPEC_ORDER.getOrDefault(item.specCode(), 9))
            .thenComparing(ReviewItem::variant, Comparator.nullsLast(Comparator.naturalOrder()));

    private final AssetMapper assetMapper;
    private final AssetReviewMapper reviewMapper;
    private final SourceMediaMapper sourceMediaMapper;
    private final ProductCatalog productCatalog;
    private final ObjectStorage storage;
    private final GenerationJobStore jobs;
    private final GenerationRunStore runs;
    private final WorkflowRegistry workflowRegistry;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom random = new SecureRandom();

    public ReviewService(AssetMapper assetMapper, AssetReviewMapper reviewMapper,
            SourceMediaMapper sourceMediaMapper, ProductCatalog productCatalog,
            ObjectStorage storage, GenerationJobStore jobs, GenerationRunStore runs,
            WorkflowRegistry workflowRegistry, AuditLog auditLog, ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate) {
        this.assetMapper = assetMapper;
        this.reviewMapper = reviewMapper;
        this.sourceMediaMapper = sourceMediaMapper;
        this.productCatalog = productCatalog;
        this.storage = storage;
        this.jobs = jobs;
        this.runs = runs;
        this.workflowRegistry = workflowRegistry;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public enum Decision {
        APPROVE,
        REJECT,
        REGENERATE
    }

    /** One asset as shown on the review board; URLs are 15-minute presigned GETs. */
    public record ReviewItem(long assetId, long productId, String sku, String productName,
            String specCode, String variant, int version, String status,
            List<PrecheckFlag> flags, Map<String, Object> metrics, String imageUrl, String thumbUrl,
            String sourceThumbUrl, String sourceUrl, String fileName) {
    }

    /** APPROVE/REJECT return the updated item; REGENERATE returns the new run. */
    public record DecisionResult(@Nullable ReviewItem item, @Nullable Long runId) {
    }

    public List<ReviewItem> list(CurrentUser user, @Nullable Long productId,
            @Nullable String status) {
        List<AssetEntity> assets = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, user.tenantId())
                .eq(productId != null, AssetEntity::getProductId, productId)
                .eq(status != null, AssetEntity::getStatus, status)
                .orderByAsc(AssetEntity::getId));
        List<ReviewItem> items = new ArrayList<>();
        for (AssetEntity asset : assets) {
            items.add(toItem(user.tenantId(), asset));
        }
        items.sort(ORDER);
        return items;
    }

    @Transactional
    public DecisionResult decide(CurrentUser user, long assetId, Decision decision,
            List<RejectReason> reasonCodes, @Nullable String comment) {
        AssetEntity asset = assetMapper.selectOne(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, user.tenantId())
                .eq(AssetEntity::getId, assetId));
        if (asset == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Asset not found");
        }
        if (!AssetStatus.IN_REVIEW.name().equals(asset.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "ASSET_NOT_IN_REVIEW",
                    "Asset " + assetId + " is " + asset.getStatus() + ", not IN_REVIEW");
        }
        List<RejectReason> reasons = reasonCodes == null ? List.of() : reasonCodes;
        if (decision == Decision.REJECT && reasons.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "REJECT requires at least one reason code");
        }
        String newStatus = switch (decision) {
            case APPROVE -> AssetStatus.APPROVED.name();
            case REJECT -> AssetStatus.REJECTED.name();
            case REGENERATE -> AssetStatus.ARCHIVED.name();
        };
        asset.setStatus(newStatus);
        assetMapper.updateById(asset);
        Long runId = decision == Decision.REGENERATE ? regenerate(user, asset) : null;
        recordReview(user, asset, decision, reasons, comment, newStatus);
        ReviewItem item = runId == null ? toItem(user.tenantId(), asset) : null;
        return new DecisionResult(item, runId);
    }

    @Transactional
    public int approveRemaining(CurrentUser user, long productId) {
        productCatalog.findById(user.tenantId(), productId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Product not found"));
        List<AssetEntity> pending = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, user.tenantId())
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getStatus, AssetStatus.IN_REVIEW.name()));
        for (AssetEntity asset : pending) {
            asset.setStatus(AssetStatus.APPROVED.name());
            assetMapper.updateById(asset);
            recordReview(user, asset, Decision.APPROVE, List.of(), null,
                    AssetStatus.APPROVED.name());
        }
        return pending.size();
    }

    /** Recreates the job that produced the asset under a fresh run. */
    private long regenerate(CurrentUser user, AssetEntity asset) {
        String pipelineRef = jdbcTemplate.queryForObject(
                "select pipeline_ref from asset_spec where code = ?", String.class,
                asset.getSpecCode());
        long runId = runs.create(user.tenantId(), asset.getProductId(), user.userId());
        switch (pipelineRef == null ? "" : pipelineRef) {
            case "SCENE" -> recreateSceneJob(user.tenantId(), asset, runId);
            case "WHITE_MAIN", "WHITE_ANGLE", "INBOX" ->
                    recreateCutoutJob(user.tenantId(), asset, runId, pipelineRef);
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "REGENERATE_NOT_SUPPORTED",
                    "Assets of spec " + asset.getSpecCode() + " cannot be regenerated yet");
        }
        return runId;
    }

    /** White pages: a fresh cutout whose only downstream is the regenerated page. */
    private void recreateCutoutJob(long tenantId, AssetEntity asset, long runId, String stepName) {
        ComfyWorkflowView workflow = workflowRegistry.active(tenantId, "CUTOUT")
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "WORKFLOW_NOT_ACTIVE",
                        "No active CUTOUT workflow", Map.of("code", "CUTOUT")));
        SourceMediaEntity source = sourceMediaFor(asset);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sourceMediaId", source.getId());
        input.put("shotCode", source.getShotCode());
        input.put("downstream", List.of(Map.of("step", stepName, "variant", asset.getVariant())));
        input.put("scenePrompts", Map.of());
        input.put("workflow", Map.of(
                "code", workflow.code(),
                "version", workflow.version(),
                "json", workflow.workflow(),
                "manifest", workflow.manifest()));
        input.put("models", workflow.manifest().models());
        input.put("params", Map.of());
        input.put("inputs", Map.of("image", source.getObjectKey()));
        input.put("outputs", Map.of());
        long jobId = jobs.create(tenantId, asset.getProductId(), runId, JobStep.CUTOUT,
                source.getShotCode(), input, List.of());
        backfillOutputs(jobId, Map.of("cutout", "t" + tenantId + "/gen/" + jobId + "/cutout.png"));
    }

    /** Scene: new seed, same scene-input outputs, original workflow. */
    private void recreateSceneJob(long tenantId, AssetEntity asset, long runId) {
        JsonNode jobIds = objectMapper.readTree(asset.getProvenanceJson()).path("jobIds");
        if (!jobIds.isArray() || jobIds.isEmpty()) {
            throw notRegeneratable(asset);
        }
        GenerationJob sceneJob = jobs.findById(jobIds.get(jobIds.size() - 1).asLong())
                .filter(job -> job.step() == JobStep.SCENE && !job.parentJobIds().isEmpty())
                .orElseThrow(() -> notRegeneratable(asset));
        GenerationJob sceneInput = jobs.findById(sceneJob.parentJobIds().get(0))
                .filter(job -> job.step() == JobStep.SCENE_INPUT)
                .orElseThrow(() -> notRegeneratable(asset));
        JsonNode input = sceneJob.input();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("positive", input.path("params").path("positive").asString());
        params.put("negative", input.path("params").path("negative").asString());
        params.put("seed", random.nextLong() & Long.MAX_VALUE);
        Map<String, Object> newInput = new LinkedHashMap<>();
        newInput.put("sourceMediaId", input.path("sourceMediaId").asLong());
        newInput.put("shotCode", input.path("shotCode").asString());
        newInput.put("workflows", input.path("workflows"));
        newInput.put("workflow", input.path("workflow"));
        newInput.put("models", input.path("models"));
        newInput.put("params", params);
        newInput.put("jobIds", input.path("jobIds"));
        newInput.put("inputs", Map.of(
                "image", sceneInput.output().path("outputs").path("image").path("objectKey")
                        .asString(),
                "mask", sceneInput.output().path("outputs").path("mask").path("objectKey")
                        .asString()));
        newInput.put("outputs", Map.of());
        long jobId = jobs.create(tenantId, asset.getProductId(), runId, JobStep.SCENE,
                sceneJob.variant(), newInput, List.of(sceneInput.id()));
        backfillOutputs(jobId, Map.of("image", "t" + tenantId + "/gen/" + jobId + "/image.png"));
    }

    private SourceMediaEntity sourceMediaFor(AssetEntity asset) {
        long sourceMediaId = firstSourceMediaId(asset);
        SourceMediaEntity source = sourceMediaId > 0 ? sourceMediaMapper.selectById(sourceMediaId)
                : null;
        if (source == null) {
            throw notRegeneratable(asset);
        }
        return source;
    }

    private static ApiException notRegeneratable(AssetEntity asset) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REGENERATE_NOT_POSSIBLE",
                "The source of asset " + asset.getId() + " is no longer available");
    }

    private void recordReview(CurrentUser user, AssetEntity asset, Decision decision,
            List<RejectReason> reasons, @Nullable String comment, String newStatus) {
        AssetReviewEntity review = new AssetReviewEntity();
        review.setTenantId(user.tenantId());
        review.setAssetId(asset.getId());
        review.setReviewer(user.userId());
        review.setDecision(decision.name());
        review.setReasonCodes(objectMapper.writeValueAsString(reasons));
        review.setComment(comment);
        reviewMapper.insert(review);
        auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                String.valueOf(user.userId()), "ASSET_REVIEWED", "asset",
                String.valueOf(asset.getId()),
                Map.of("status", AssetStatus.IN_REVIEW.name()),
                Map.of("status", newStatus, "decision", decision.name(), "reasonCodes", reasons),
                comment, "REVIEW"));
    }

    private ReviewItem toItem(long tenantId, AssetEntity asset) {
        ProductView product = productCatalog.findById(tenantId, asset.getProductId()).orElse(null);
        JsonNode precheck = objectMapper.readTree(asset.getPrecheckJson());
        List<PrecheckFlag> flags = new ArrayList<>();
        for (JsonNode flag : precheck.path("flags")) {
            flags.add(PrecheckFlag.valueOf(flag.asString()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = objectMapper.treeToValue(precheck.path("metrics"), Map.class);
        String sourceUrl = null;
        String sourceThumbUrl = null;
        long sourceMediaId = firstSourceMediaId(asset);
        if (sourceMediaId > 0) {
            SourceMediaEntity media = sourceMediaMapper.selectById(sourceMediaId);
            if (media != null) {
                sourceUrl = presign(media.getObjectKey());
                sourceThumbUrl = media.getThumbObjectKey() != null
                        ? presign(media.getThumbObjectKey()) : sourceUrl;
            }
        }
        return new ReviewItem(asset.getId(), asset.getProductId(),
                product == null ? null : product.sku(), product == null ? null : product.name(),
                asset.getSpecCode(), asset.getVariant(), asset.getVersion(), asset.getStatus(),
                flags, metrics, presign(asset.getObjectKey()), presign(asset.getThumbObjectKey()),
                sourceThumbUrl, sourceUrl, asset.getFileName());
    }

    private long firstSourceMediaId(AssetEntity asset) {
        JsonNode ids = objectMapper.readTree(asset.getProvenanceJson()).path("sourceMediaIds");
        return ids.isArray() && !ids.isEmpty() && ids.get(0).isNumber() ? ids.get(0).asLong() : 0;
    }

    private @Nullable String presign(@Nullable String objectKey) {
        return objectKey == null ? null : storage.presignGet(objectKey, URL_TTL).toString();
    }

    /** Replaces input_json.outputs after the job id is known. */
    private void backfillOutputs(long jobId, Map<String, String> outputs) {
        jdbcTemplate.update(
                "update generation_job set input_json = jsonb_set(input_json, '{outputs}', ?::jsonb) "
                        + "where id = ?",
                objectMapper.writeValueAsString(outputs), jobId);
    }
}
