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
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Task 8: after a {@link ProductPriceChanged} commits, every AD_STATIC variant
 * that depends on the price gets its latest version marked STALE (when it was
 * APPROVED or PUBLISHED - the id seeds the auto-approval check) or ARCHIVED
 * (when still IN_REVIEW or DRAFT) and an {@code AD_RENDER} task is enqueued
 * with priceOnly so the new price re-renders. Variants without an existing
 * price-dependent asset are left untouched.
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
    public void onPriceChanged(ProductPriceChanged event) {
        long tenantId = event.tenantId();
        long productId = event.productId();
        Map<String, AssetEntity> latestByVariant = latestPriceVariants(tenantId, productId);
        if (latestByVariant.isEmpty()) {
            return;
        }
        List<String> variants = new ArrayList<>();
        Map<String, Long> autoApproveFrom = new LinkedHashMap<>();
        int staled = 0;
        int archived = 0;
        for (Map.Entry<String, AssetEntity> entry : latestByVariant.entrySet()) {
            AssetEntity asset = entry.getValue();
            switch (asset.getStatus()) {
                case "APPROVED", "PUBLISHED", "STALE" -> {
                    asset.setStatus(AssetStatus.STALE.name());
                    assetMapper.updateById(asset);
                    autoApproveFrom.put(entry.getKey(), asset.getId());
                    staled++;
                }
                case "IN_REVIEW", "DRAFT" -> {
                    asset.setStatus(AssetStatus.ARCHIVED.name());
                    assetMapper.updateById(asset);
                    archived++;
                }
                default -> {
                    // REJECTED and ARCHIVED versions are left as-is and still re-rendered
                }
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
        queue.enqueue(tenantId, AdRenderTaskHandler.TYPE, payload,
                "ad-render:" + productId + ":price:" + String.join(",", variants));
    }

    /** The latest version of every AD_STATIC variant with depends_on_price = true. */
    private Map<String, AssetEntity> latestPriceVariants(long tenantId, long productId) {
        List<AssetEntity> rows = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "AD_STATIC")
                .eq(AssetEntity::getDependsOnPrice, true)
                .orderByAsc(AssetEntity::getVersion));
        Map<String, AssetEntity> latest = new LinkedHashMap<>();
        for (AssetEntity row : rows) {
            latest.put(row.getVariant(), row); // ascending version: the last one wins
        }
        return latest;
    }
}