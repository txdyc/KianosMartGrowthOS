package com.kiano.content.derive;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.asset.MainImageApprovedEvent;
import com.kiano.content.copy.CopyGenerationTaskHandler;
import com.kiano.content.facts.FactLockedEvent;
import com.kiano.content.facts.FactSheetService;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.queue.TaskQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * After a fact sheet is LOCKED (Task 7): version-dependent assets
 * (COPY_* / PAGE_INFO / PAGE_SPEC) whose fact_version is older are archived
 * or marked STALE, and new COPY_GENERATE + TEMPLATE_RENDER tasks are
 * enqueued. PAGE_INFO waits for an approved PAGE_MAIN (green-lighted by
 * {@link MainImageApprovedEvent}) so it can render the product image.
 */
@Component
public class FactDependentAssets {

    private static final List<String> DERIVED_SPECS =
            List.of("COPY_TITLE", "COPY_SHORT", "COPY_LONG", "COPY_SEO", "COPY_GSHOP",
                    "COPY_WA", "PAGE_INFO", "PAGE_SPEC");

    private final AssetMapper assetMapper;
    private final FactSheetService factSheetService;
    private final TaskQueue queue;
    private final AuditLog auditLog;

    public FactDependentAssets(AssetMapper assetMapper, FactSheetService factSheetService,
            TaskQueue queue, AuditLog auditLog) {
        this.assetMapper = assetMapper;
        this.factSheetService = factSheetService;
        this.queue = queue;
        this.auditLog = auditLog;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFactLocked(FactLockedEvent event) {
        long tenantId = event.tenantId();
        long productId = event.productId();
        int version = event.version();
        List<AssetEntity> stale = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .in(AssetEntity::getSpecCode, DERIVED_SPECS)
                .lt(AssetEntity::getFactVersion, version));
        int archived = 0;
        int staled = 0;
        for (AssetEntity asset : stale) {
            boolean published = AssetStatus.PUBLISHED.name().equals(asset.getStatus());
            asset.setStatus(published ? AssetStatus.STALE.name() : AssetStatus.ARCHIVED.name());
            assetMapper.updateById(asset);
            if (published) {
                staled++;
            } else {
                archived++;
            }
        }
        auditLog.record(new AuditEntry(tenantId, ActorType.SYSTEM, "SYSTEM",
                "ASSETS_STALE_BY_FACTS", "product", String.valueOf(productId),
                Map.of("factVersion", version, "archived", archived, "staled", staled),
                null, null, "FACTS"));
        queue.enqueue(tenantId, CopyGenerationTaskHandler.TYPE,
                Map.of("productId", productId, "factVersion", version),
                "copy:" + productId + ":" + version);
        queue.enqueue(tenantId, TemplateRenderTaskHandler.TYPE,
                Map.of("productId", productId, "factVersion", version, "spec", "PAGE_SPEC"),
                "template-render:" + productId + ":" + version + ":PAGE_SPEC");
        if (hasApprovedMain(tenantId, productId)) {
            queue.enqueue(tenantId, TemplateRenderTaskHandler.TYPE,
                    Map.of("productId", productId, "factVersion", version, "spec", "PAGE_INFO"),
                    "template-render:" + productId + ":" + version + ":PAGE_INFO");
        }
    }

    /** Page-info render only kicks in once the main image is approved AND facts are locked. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMainApproved(MainImageApprovedEvent event) {
        factSheetService.locked(event.tenantId(), event.productId())
                .ifPresent(locked -> queue.enqueue(event.tenantId(), TemplateRenderTaskHandler.TYPE,
                        Map.of("productId", event.productId(), "factVersion", locked.version(),
                                "spec", "PAGE_INFO"),
                        "template-render:" + event.productId() + ":" + locked.version()
                                + ":PAGE_INFO"));
    }

    private boolean hasApprovedMain(long tenantId, long productId) {
        List<AssetEntity> mains = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "PAGE_MAIN")
                .eq(AssetEntity::getStatus, AssetStatus.APPROVED.name())
                .last("limit 1"));
        return !mains.isEmpty();
    }
}