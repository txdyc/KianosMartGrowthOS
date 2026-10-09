package com.kiano.content.asset;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kiano.content.ads.AdRenderMeta;
import com.kiano.content.copy.CopyAssembler;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.JobStatus;
import com.kiano.content.video.EdlMath;
import com.kiano.content.video.VideoProperties;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.workerprotocol.Edl;
import com.kiano.workerprotocol.JobStep;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns finished generation jobs into versioned assets: downloads the PNG,
 * encodes JPEG (quality 92) plus a 400px thumbnail, runs the precheck,
 * archives superseded IN_REVIEW/DRAFT versions and writes the ASSET_CREATED
 * audit row.
 */
@Component
public class AssetService {

    private static final float JPEG_QUALITY = 0.92f;
    private static final int THUMBNAIL_LONG_SIDE = 400;

    private final AssetMapper mapper;
    private final ObjectStorage storage;
    private final Precheck precheck;
    private final GenerationJobStore jobs;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;
    private final VideoProperties videoProperties;

    public AssetService(AssetMapper mapper, ObjectStorage storage, Precheck precheck,
            GenerationJobStore jobs, AuditLog auditLog, ObjectMapper objectMapper,
            VideoProperties videoProperties) {
        this.mapper = mapper;
        this.storage = storage;
        this.precheck = precheck;
        this.jobs = jobs;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        this.videoProperties = videoProperties;
    }

    @Transactional
    public AssetView createFromJob(GenerationJob job, String sku, Map<String, Object> provenance) {
        String objectKey = job.output().path("outputs").path("image").path("objectKey").asString();
        BufferedImage image = ImageCodec.read(storage.download(objectKey));
        byte[] full = ImageCodec.jpeg(image, JPEG_QUALITY);
        byte[] thumb = ImageCodec.thumbnailJpeg(image, THUMBNAIL_LONG_SIDE);

        String specCode = job.step().assetSpecCode();
        int version = nextVersion(job.tenantId(), job.productId(), specCode, job.variant());
        String prefix = "t" + job.tenantId() + "/assets/" + job.productId() + "/" + specCode
                + "/" + job.variant() + "/v" + version;
        String fullKey = prefix + ".jpg";
        String thumbKey = prefix + "_thumb.jpg";
        storage.put(fullKey, full, "image/jpeg");
        storage.put(thumbKey, thumb, "image/jpeg");

        PrecheckResult result = runPrecheck(job, image);
        String fileName = AssetFileName.format(sku, angleSegment(job), typeSegment(job),
                image.getWidth(), image.getHeight(), version, "jpg");

        AssetEntity entity = new AssetEntity();
        entity.setTenantId(job.tenantId());
        entity.setProductId(job.productId());
        entity.setSpecCode(specCode);
        entity.setVariant(job.variant());
        entity.setVersion(version);
        entity.setKind("IMAGE");
        entity.setObjectKey(fullKey);
        entity.setThumbObjectKey(thumbKey);
        entity.setWidth(image.getWidth());
        entity.setHeight(image.getHeight());
        entity.setStatus(AssetStatus.IN_REVIEW.name());
        entity.setPrecheckJson(objectMapper.writeValueAsString(Map.of(
                "flags", result.flags().stream().map(Enum::name).toList(),
                "metrics", result.metrics())));
        entity.setProvenanceJson(objectMapper.writeValueAsString(provenance));
        entity.setFileName(fileName);
        entity.setRunId(job.runId());
        entity.setCreatedAt(OffsetDateTime.now());
        mapper.insert(entity);

        mapper.update(null, new LambdaUpdateWrapper<AssetEntity>()
                .eq(AssetEntity::getTenantId, job.tenantId())
                .eq(AssetEntity::getProductId, job.productId())
                .eq(AssetEntity::getSpecCode, specCode)
                .eq(AssetEntity::getVariant, job.variant())
                .in(AssetEntity::getStatus, AssetStatus.DRAFT.name(), AssetStatus.IN_REVIEW.name())
                .ne(AssetEntity::getId, entity.getId())
                .set(AssetEntity::getStatus, AssetStatus.ARCHIVED.name()));

        List<String> flagNames = result.flags().stream().map(Enum::name).toList();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("fileName", fileName);
        after.put("specCode", specCode);
        after.put("version", version);
        after.put("status", AssetStatus.IN_REVIEW.name());
        after.put("flags", flagNames);
        auditLog.record(new AuditEntry(job.tenantId(), ActorType.SYSTEM, "pipeline",
                "ASSET_CREATED", "asset", String.valueOf(entity.getId()), null, after,
                null, "PIPELINE"));

        return new AssetView(entity.getId(), job.productId(), specCode, job.variant(), version,
                entity.getStatus(), result.flags(), result.metrics(), fileName,
                sourceMediaId(provenance), entity.getCreatedAt().toInstant());
    }

    /**
     * Turns a finished VIDEO_COMPOSE job into a VIDEO_AD asset (C5 Task 9):
     * copies the video, the no-music rendition and the poster into the asset
     * directory ({@code renditions_json}), makes the thumbnail from the poster,
     * takes the duration the worker measured, and records the AI ratio. A video
     * whose AI share exceeds {@code max-ai-ratio} is REJECTED with the
     * AI_RATIO_EXCEEDED flag and the VIDEO_BLOCKED_AI_RATIO audit instead of
     * entering review. Asset reviews need a human reviewer, so the block is
     * recorded through the flag and the audit row (C5 Global Constraints).
     */
    @Transactional
    public AssetView createVideoFromJob(GenerationJob job, String sku,
            Map<String, Object> provenance) {
        Edl edl = objectMapper.treeToValue(job.input().path("edl"), Edl.class);
        JsonNode outputs = job.output().path("outputs");
        String videoKey = outputs.path("video").path("objectKey").asString();
        String noMusicKey = outputs.path("video_nomusic").path("objectKey").asString();
        String posterKey = outputs.path("poster").path("objectKey").asString();
        Double durationS = outputs.path("video").path("durationS").isNumber()
                ? outputs.path("video").path("durationS").asDouble() : null;

        String variant = job.variant();
        int version = nextVersion(job.tenantId(), job.productId(), "VIDEO_AD", variant);
        String prefix = "t" + job.tenantId() + "/assets/" + job.productId() + "/VIDEO_AD/"
                + variant + "/v" + version;
        String fullKey = prefix + ".mp4";
        String noMusicAssetKey = prefix + "_nomusic.mp4";
        String posterAssetKey = prefix + "_poster.jpg";
        String thumbKey = prefix + "_thumb.jpg";
        storage.put(fullKey, storage.download(videoKey), "video/mp4");
        storage.put(noMusicAssetKey, storage.download(noMusicKey), "video/mp4");
        byte[] poster = storage.download(posterKey);
        storage.put(posterAssetKey, poster, "image/jpeg");
        storage.put(thumbKey, ImageCodec.thumbnailJpeg(ImageCodec.read(poster),
                THUMBNAIL_LONG_SIDE), "image/jpeg");

        double aiRatio = EdlMath.aiRatio(edl);
        boolean mixed = EdlMath.mixed(edl);
        boolean blocked = aiRatio > videoProperties.getMaxAiRatio();
        List<PrecheckFlag> flags = blocked
                ? List.of(PrecheckFlag.AI_RATIO_EXCEEDED) : List.of();
        String fileName = AssetFileName.format(sku, variant, mixed ? "mixed" : "real",
                (int) edl.width(), (int) edl.height(), version, "mp4");

        AssetEntity entity = new AssetEntity();
        entity.setTenantId(job.tenantId());
        entity.setProductId(job.productId());
        entity.setSpecCode("VIDEO_AD");
        entity.setVariant(variant);
        entity.setVersion(version);
        entity.setKind("VIDEO");
        entity.setObjectKey(fullKey);
        entity.setThumbObjectKey(thumbKey);
        entity.setWidth((int) edl.width());
        entity.setHeight((int) edl.height());
        entity.setDurationS(durationS == null ? null
                : java.math.BigDecimal.valueOf(durationS)
                        .setScale(2, java.math.RoundingMode.HALF_UP));
        entity.setAiRatio(java.math.BigDecimal.valueOf(aiRatio));
        entity.setDependsOnPrice(true);
        entity.setPriceSnapshot(priceSnapshot(provenance));
        entity.setRenditionsJson(objectMapper.writeValueAsString(Map.of(
                "nomusic", noMusicAssetKey, "poster", posterAssetKey)));
        entity.setStatus((blocked ? AssetStatus.REJECTED : AssetStatus.IN_REVIEW).name());
        entity.setPrecheckJson(objectMapper.writeValueAsString(Map.of(
                "flags", flags.stream().map(Enum::name).toList(), "metrics", Map.of())));
        entity.setProvenanceJson(objectMapper.writeValueAsString(provenance));
        entity.setFileName(fileName);
        entity.setRunId(job.runId());
        entity.setCreatedAt(OffsetDateTime.now());
        mapper.insert(entity);

        mapper.update(null, new LambdaUpdateWrapper<AssetEntity>()
                .eq(AssetEntity::getTenantId, job.tenantId())
                .eq(AssetEntity::getProductId, job.productId())
                .eq(AssetEntity::getSpecCode, "VIDEO_AD")
                .eq(AssetEntity::getVariant, variant)
                .in(AssetEntity::getStatus, AssetStatus.DRAFT.name(), AssetStatus.IN_REVIEW.name())
                .ne(AssetEntity::getId, entity.getId())
                .set(AssetEntity::getStatus, AssetStatus.ARCHIVED.name()));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("fileName", fileName);
        after.put("specCode", "VIDEO_AD");
        after.put("version", version);
        after.put("status", entity.getStatus());
        after.put("aiRatio", aiRatio);
        after.put("flags", flags.stream().map(Enum::name).toList());
        auditLog.record(new AuditEntry(job.tenantId(), ActorType.SYSTEM, "pipeline",
                "ASSET_CREATED", "asset", String.valueOf(entity.getId()), null, after, null, "VIDEO"));
        if (blocked) {
            auditLog.record(new AuditEntry(job.tenantId(), ActorType.SYSTEM, "SYSTEM",
                    "VIDEO_BLOCKED_AI_RATIO", "asset", String.valueOf(entity.getId()), null,
                    Map.of("aiRatio", aiRatio, "maxAiRatio", videoProperties.getMaxAiRatio()),
                    "AI_RATIO_EXCEEDED", "VIDEO"));
        }

        return new AssetView(entity.getId(), job.productId(), "VIDEO_AD", variant, version,
                entity.getStatus(), flags, Map.of(), fileName, sourceMediaId(provenance),
                entity.getCreatedAt().toInstant());
    }

    private static @Nullable BigDecimal priceSnapshot(Map<String, Object> provenance) {
        Object price = provenance == null ? null : provenance.get("price");
        if (price instanceof Map<?, ?> map && map.get("snapshot") instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    @Transactional
    public AssetView createText(long tenantId, long productId, String specCode, String variant,
            CopyAssembler.TextAsset text, int factVersion, Map<String, Object> provenance,
            PrecheckResult precheck) {
        int version = nextVersion(tenantId, productId, specCode, variant);
        AssetEntity entity = new AssetEntity();
        entity.setTenantId(tenantId);
        entity.setProductId(productId);
        entity.setSpecCode(specCode);
        entity.setVariant(variant);
        entity.setVersion(version);
        entity.setKind("TEXT");
        entity.setTextBody(text.textBody());
        entity.setContentJson(objectMapper.writeValueAsString(text.contentJson()));
        entity.setStatus(AssetStatus.IN_REVIEW.name());
        entity.setPrecheckJson(objectMapper.writeValueAsString(Map.of(
                "flags", precheck.flags().stream().map(Enum::name).toList(),
                "metrics", precheck.metrics())));
        entity.setProvenanceJson(objectMapper.writeValueAsString(provenance));
        entity.setFactVersion(factVersion);
        entity.setCreatedAt(OffsetDateTime.now());
        mapper.insert(entity);

        mapper.update(null, new LambdaUpdateWrapper<AssetEntity>()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, specCode)
                .eq(AssetEntity::getVariant, variant)
                .in(AssetEntity::getStatus, AssetStatus.DRAFT.name(), AssetStatus.IN_REVIEW.name())
                .ne(AssetEntity::getId, entity.getId())
                .set(AssetEntity::getStatus, AssetStatus.ARCHIVED.name()));

        auditLog.record(new AuditEntry(tenantId, ActorType.SYSTEM, "pipeline",
                "ASSET_CREATED", "asset", String.valueOf(entity.getId()), null,
                Map.of("specCode", specCode, "version", version,
                        "status", AssetStatus.IN_REVIEW.name(),
                        "flags", precheck.flags().stream().map(Enum::name).toList()),
                null, "COPY"));
        return new AssetView(entity.getId(), productId, specCode, variant, version,
                entity.getStatus(), precheck.flags(), precheck.metrics(), null, null,
                entity.getCreatedAt().toInstant());
    }

    /**
     * Saves a template-rendered PNG (PAGE_INFO/PAGE_SPEC) as a JPEG asset with
     * thumbnail in IN_REVIEW. These are deterministically rendered (no AI), so
     * precheck is skipped; the file name uses angle page-info/page-spec, type
     * real and the standard variant "default".
     */
    @Transactional
    public AssetView createImageFromBytes(long tenantId, long productId, String specCode,
            String variant, byte[] png, int factVersion, Map<String, Object> provenance,
            String sku) {
        return createImageFromBytes(tenantId, productId, specCode, variant, png, factVersion,
                provenance, sku, null);
    }

    /**
     * Ad-static variant: the file name uses the hook as angle segment and the
     * base's source type as type segment, and the depends_on_price / price
     * snapshot columns are written (pricehook only).
     */
    @Transactional
    public AssetView createImageFromBytes(long tenantId, long productId, String specCode,
            String variant, byte[] png, int factVersion, Map<String, Object> provenance,
            String sku, AdRenderMeta adMeta) {
        BufferedImage image = ImageCodec.read(png);
        byte[] full = ImageCodec.jpeg(image, JPEG_QUALITY);
        byte[] thumb = ImageCodec.thumbnailJpeg(image, THUMBNAIL_LONG_SIDE);
        int version = nextVersion(tenantId, productId, specCode, variant);
        String prefix = "t" + tenantId + "/assets/" + productId + "/" + specCode + "/"
                + variant + "/v" + version;
        String fullKey = prefix + ".jpg";
        String thumbKey = prefix + "_thumb.jpg";
        storage.put(fullKey, full, "image/jpeg");
        storage.put(thumbKey, thumb, "image/jpeg");

        String fileName;
        if (adMeta != null) {
            String hook = variant.contains("-")
                    ? variant.substring(0, variant.indexOf('-')) : variant;
            fileName = AssetFileName.format(sku, hook, adMeta.fileType(),
                    image.getWidth(), image.getHeight(), version, "jpg");
        } else {
            String angle = "PAGE_SPEC".equals(specCode) ? "page-spec" : "page-info";
            fileName = AssetFileName.format(sku, angle, "real",
                    image.getWidth(), image.getHeight(), version, "jpg");
        }
        AssetEntity entity = new AssetEntity();
        entity.setTenantId(tenantId);
        entity.setProductId(productId);
        entity.setSpecCode(specCode);
        entity.setVariant(variant);
        entity.setVersion(version);
        entity.setKind("IMAGE");
        entity.setObjectKey(fullKey);
        entity.setThumbObjectKey(thumbKey);
        entity.setWidth(image.getWidth());
        entity.setHeight(image.getHeight());
        entity.setStatus(AssetStatus.IN_REVIEW.name());
        entity.setPrecheckJson(objectMapper.writeValueAsString(Map.of(
                "flags", List.of(), "metrics", Map.of())));
        entity.setProvenanceJson(objectMapper.writeValueAsString(provenance));
        entity.setFactVersion(factVersion);
        entity.setFileName(fileName);
        entity.setCreatedAt(OffsetDateTime.now());
        if (adMeta != null) {
            entity.setDependsOnPrice(adMeta.dependsOnPrice());
            entity.setPriceSnapshot(adMeta.priceSnapshot());
        }
        mapper.insert(entity);

        mapper.update(null, new LambdaUpdateWrapper<AssetEntity>()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, specCode)
                .eq(AssetEntity::getVariant, variant)
                .in(AssetEntity::getStatus, AssetStatus.DRAFT.name(), AssetStatus.IN_REVIEW.name())
                .ne(AssetEntity::getId, entity.getId())
                .set(AssetEntity::getStatus, AssetStatus.ARCHIVED.name()));
        auditLog.record(new AuditEntry(tenantId, ActorType.SYSTEM, "pipeline",
                "ASSET_CREATED", "asset", String.valueOf(entity.getId()), null,
                Map.of("specCode", specCode, "version", version), null, "TEMPLATE"));
        return new AssetView(entity.getId(), productId, specCode, variant, version,
                entity.getStatus(), List.of(), Map.of(), fileName, null,
                entity.getCreatedAt().toInstant());
    }

    private PrecheckResult runPrecheck(GenerationJob job, BufferedImage image) {
        return switch (job.step()) {
            case WHITE_MAIN, WHITE_ANGLE -> precheck.white(image, true);
            case INBOX -> precheck.white(image, false);
            case SCENE -> scenePrecheck(job, image);
            default -> noCheck();
        };
    }

    /** SCENE needs the parent SCENE_INPUT composite (image + product mask). */
    private PrecheckResult scenePrecheck(GenerationJob job, BufferedImage output) {
        for (long parentId : job.parentJobIds()) {
            GenerationJob parent = jobs.findById(parentId).orElse(null);
            if (parent == null || parent.step() != JobStep.SCENE_INPUT
                    || parent.status() != JobStatus.SUCCEEDED) {
                continue;
            }
            JsonNode outputs = parent.output().path("outputs");
            BufferedImage sceneInput = ImageCodec.read(
                    storage.download(outputs.path("image").path("objectKey").asString()));
            BufferedImage mask = ImageCodec.read(
                    storage.download(outputs.path("mask").path("objectKey").asString()));
            return precheck.scene(output, sceneInput, mask);
        }
        return noCheck();
    }

    private static PrecheckResult noCheck() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("ssim", null);
        metrics.put("occupancy", null);
        metrics.put("ocrWords", 0);
        metrics.put("ocr", "SKIPPED");
        return new PrecheckResult(List.of(), metrics);
    }

    /** Export angle segment: WHITE_MAIN is fixed, variants are lowercased. */
    private static String angleSegment(GenerationJob job) {
        return switch (job.step()) {
            case WHITE_MAIN -> "page-main";
            case WHITE_ANGLE, SCENE -> "page-" + job.variant().toLowerCase();
            case INBOX -> "page-inbox";
            default -> job.variant().toLowerCase();
        };
    }

    private static String typeSegment(GenerationJob job) {
        return job.step() == JobStep.SCENE ? "mixed" : "real";
    }

    private static @Nullable Long sourceMediaId(Map<String, Object> provenance) {
        Object ids = provenance.get("sourceMediaIds");
        if (ids instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private int nextVersion(long tenantId, long productId, String specCode, String variant) {
        AssetEntity latest = mapper.selectOne(new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, specCode)
                .eq(AssetEntity::getVariant, variant)
                .orderByDesc(AssetEntity::getVersion)
                .last("limit 1"));
        return latest == null ? 1 : latest.getVersion() + 1;
    }

    /** One generated asset as seen by the pipeline caller. */
    public record AssetView(long id, long productId, String specCode, String variant, int version,
            String status, List<PrecheckFlag> flags, Map<String, Object> metrics, String fileName,
            @Nullable Long sourceMediaId, Instant createdAt) {
    }
}
