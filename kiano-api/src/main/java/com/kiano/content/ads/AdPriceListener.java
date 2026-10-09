package com.kiano.content.ads;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductPriceChanged;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetStatus;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.queue.TaskQueue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Task 8: after a {@link ProductPriceChanged} commits, every AD_STATIC variant
 * that depends on the price is re-rendered with priceOnly. Every APPROVED or
 * PUBLISHED version of such a variant is marked STALE - not only the latest,
 * since an older approved version would otherwise still be exported with the
 * old price - and the newest live/stale version seeds the auto-approval check.
 * IN_REVIEW and DRAFT versions are ARCHIVED. Variants without a price-dependent
 * asset are left untouched.
 *
 * <p>When a re-render with the same variants is already active (it may be
 * RUNNING with the old price) a single follow-up task is queued behind it;
 * the render skips variants that already show the current price. The work runs
 * in its own transaction: AFTER_COMMIT code would otherwise still share the
 * finished transaction's connection.
 */
@Component
public class AdPriceListener {

    private final AssetMapper assetMapper;
    private final TaskQueue queue;
    private final AuditLog auditLog;

    public AdPriceListener(AssetMapper assetMapper, TaskQueue queue, AuditLog auditLog) {
        this.assetMapper = assetMapper;
        this.queue = queue;
        this.auditLog = auditLog;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPriceChanged(ProductPriceChanged event) {
        long tenantId = event.tenantId();
        long productId = event.productId();
        Map<String, List<AssetEntity>> byVariant = priceVariants(tenantId, productId);
        if (byVariant.isEmpty()) {
            return;
        }
        List<String> variants = new ArrayList<>();
        Map<String, Long> autoApproveFrom = new LinkedHashMap<>();
        int staled = 0;
        int archived = 0;
        for (Map.Entry<String, List<AssetEntity>> entry : byVariant.entrySet()) {
            Long baseline = null;
            for (AssetEntity asset : entry.getValue()) { // newest version first
                switch (asset.getStatus()) {
                    case "APPROVED", "PUBLISHED" -> {
                        asset.setStatus(AssetStatus.STALE.name());
                        assetMapper.updateById(asset);
                        staled++;
                        baseline = baseline == null ? asset.getId() : baseline;
                    }
                    case "STALE" -> baseline = baseline == null ? asset.getId() : baseline;
                    case "IN_REVIEW", "DRAFT" -> {
                        asset.setStatus(AssetStatus.ARCHIVED.name());
                        assetMapper.updateById(asset);
                        archived++;
                    }
                    default -> {
                        // REJECTED and ARCHIVED versions are left as-is
                    }
                }
            }
            if (baseline != null) {
                autoApproveFrom.put(entry.getKey(), baseline);
            }
            variants.add(entry.getKey());
        }
        auditLog.record(new AuditEntry(tenantId, ActorType.SYSTEM, "SYSTEM",
                "ADS_STALE_BY_PRICE", "product", String.valueOf(productId), null,
                Map.of("variants", variants, "staled", staled, "archived", archived),
                null, "ADS"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId);
        payload.put("variants", variants);
        payload.put("priceOnly", true);
        if (!autoApproveFrom.isEmpty()) {
            payload.put("autoApproveFrom", autoApproveFrom);
        }
        String dedupeKey = "ad-render:" + productId + ":price:" + String.join(",", variants);
        if (queue.enqueue(tenantId, AdRenderTaskHandler.TYPE, payload, dedupeKey).isEmpty()) {
            // the twin may be RUNNING and have read the old price already, so queue
            // one follow-up behind it (cheap when the twin was only QUEUED: the
            // render skips variants that already show the current price)
            queue.enqueue(tenantId, AdRenderTaskHandler.TYPE, payload, dedupeKey + ":followup");
        }
    }

    /** Every version of every AD_STATIC variant with depends_on_price, newest first. */
    private Map<String, List<AssetEntity>> priceVariants(long tenantId, long productId) {
        List<AssetEntity> rows = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "AD_STATIC")
                .eq(AssetEntity::getDependsOnPrice, true)
                .orderByAsc(AssetEntity::getVariant)
                .orderByDesc(AssetEntity::getVersion));
        Map<String, List<AssetEntity>> byVariant = new LinkedHashMap<>();
        for (AssetEntity row : rows) {
            byVariant.computeIfAbsent(row.getVariant(), key -> new ArrayList<>()).add(row);
        }
        return byVariant;
    }
}
