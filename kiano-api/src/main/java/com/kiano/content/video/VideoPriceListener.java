package com.kiano.content.video;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductPriceChanged;
import com.kiano.commerce.ProductView;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.workerprotocol.Edl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A price change staleness the videos that show it (C5 Task 11, Review Focus 5):
 * for every type, the latest APPROVED/PUBLISHED version becomes STALE (an
 * IN_REVIEW/DRAFT one is archived), and only the end card is re-rendered - the
 * captions, the EDL, the H3 clip and the music are reused, so the new cut
 * differs from the old one by nothing but the price and it can auto-approve in
 * {@link com.kiano.content.asset.AssetService#createVideoFromJob}. The new
 * VIDEO_COMPOSE job stays queued while the worker is offline and the old
 * version stays STALE until it finishes.
 */
@Component
public class VideoPriceListener {

    private static final Logger log = LoggerFactory.getLogger(VideoPriceListener.class);

    private final AssetMapper assetMapper;
    private final GenerationRunStore runs;
    private final VideoOverlayRenderer overlays;
    private final ProductCatalog products;
    private final ObjectStorage storage;
    private final VideoJobFactory jobFactory;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;

    public VideoPriceListener(AssetMapper assetMapper, GenerationRunStore runs,
            VideoOverlayRenderer overlays, ProductCatalog products, ObjectStorage storage,
            VideoJobFactory jobFactory, AuditLog auditLog, ObjectMapper objectMapper) {
        this.assetMapper = assetMapper;
        this.runs = runs;
        this.overlays = overlays;
        this.products = products;
        this.storage = storage;
        this.jobFactory = jobFactory;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPriceChanged(ProductPriceChanged event) {
        ProductView product = products.findById(event.tenantId(), event.productId()).orElse(null);
        if (product == null) {
            return;
        }
        List<String> staled = new ArrayList<>();
        List<String> archived = new ArrayList<>();
        for (VideoType type : VideoType.values()) {
            AssetEntity baseline = staleOutdatedVersions(event.tenantId(), event.productId(), type,
                    staled, archived);
            if (baseline == null) {
                continue;
            }
            try {
                recompose(event.tenantId(), product, type, baseline);
            } catch (RuntimeException ex) {
                log.warn("Could not queue the price re-render of {} {}: {}",
                        product.sku(), type.wire(), ex.getMessage());
            }
        }
        if (staled.isEmpty() && archived.isEmpty()) {
            return;
        }
        auditLog.record(new AuditEntry(event.tenantId(), ActorType.SYSTEM, "SYSTEM",
                "VIDEOS_STALE_BY_PRICE", "product", String.valueOf(event.productId()), null,
                Map.of("variants", staled, "archived", archived), null, "VIDEO"));
    }

    /**
     * APPROVED/PUBLISHED versions of this type become STALE, IN_REVIEW/DRAFT
     * ones are archived; the newest STALE version is the auto-approve baseline.
     */
    private @Nullable AssetEntity staleOutdatedVersions(long tenantId, long productId,
            VideoType type, List<String> staled, List<String> archived) {
        List<AssetEntity> versions = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "VIDEO_AD")
                .eq(AssetEntity::getVariant, type.wire())
                .orderByDesc(AssetEntity::getVersion));
        AssetEntity baseline = null;
        for (AssetEntity asset : versions) {
            String status = asset.getStatus();
            if (AssetStatus.APPROVED.name().equals(status)
                    || AssetStatus.PUBLISHED.name().equals(status)) {
                setStatus(asset, AssetStatus.STALE);
                staled.add(type.wire());
                baseline = baseline == null ? asset : baseline;
            } else if (AssetStatus.IN_REVIEW.name().equals(status)
                    || AssetStatus.DRAFT.name().equals(status)) {
                setStatus(asset, AssetStatus.ARCHIVED);
                archived.add(type.wire());
            } else if (AssetStatus.STALE.name().equals(status) && baseline == null) {
                baseline = asset;
                staled.add(type.wire());
            }
        }
        return baseline;
    }

    private void setStatus(AssetEntity asset, AssetStatus status) {
        asset.setStatus(status.name());
        assetMapper.updateById(asset);
    }

    /** New run, new end card, otherwise the old cut: a compose job that can auto-approve. */
    private void recompose(long tenantId, ProductView product, VideoType type, AssetEntity old) {
        JsonNode provenance = objectMapper.readTree(old.getProvenanceJson());
        Edl edl = objectMapper.treeToValue(provenance.path("edl"), Edl.class);
        Map<String, String> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : provenance.path("inputs").properties()) {
            inputs.put(entry.getKey(), entry.getValue().asString());
        }
        long runId = runs.create(tenantId, product.id(), null, "VIDEO");
        String base = "t" + tenantId + "/gen/video/" + runId + "/" + type.wire() + "/";
        String endCardKey = "t" + tenantId + "/video-inputs/" + runId + "/" + type.wire()
                + "/endcard.png";
        VideoOverlayRenderer.EndCard endCard = overlays.renderEndCard(tenantId, product);
        storage.put(endCardKey, endCard.png(), "image/png");
        inputs.put("endcard", endCardKey);
        Map<String, String> outputs = Map.of(
                "video", base + "video.mp4",
                "video_nomusic", base + "video_nomusic.mp4",
                "poster", base + "poster.jpg");
        Map<String, Object> seeds = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> oldSeeds = objectMapper.treeToValue(provenance, Map.class);
        seeds.putAll(oldSeeds);
        seeds.put("price", endCard.price());
        Map<String, Object> templates = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> oldTemplates = objectMapper.treeToValue(
                provenance.path("templates"), Map.class);
        if (oldTemplates != null) {
            templates.putAll(oldTemplates);
        }
        templates.put("END_CARD", endCard.templateVersion());
        seeds.put("templates", templates);
        jobFactory.createCompose(tenantId, product.id(), runId, type.wire(),
                "video:" + runId + ":" + type.wire(), edl, inputs, outputs, seeds, old.getId());
    }
}