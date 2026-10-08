package com.kiano.content.copy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetService;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.asset.PrecheckResult;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactSheetService.FactSheetView;
import com.kiano.content.facts.FactsJson;
import com.kiano.content.policy.PolicyChangedEvent;
import com.kiano.content.policy.PolicyService;
import com.kiano.content.template.TemplateRegistry;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import com.kiano.platform.queue.TaskHandler;
import com.kiano.platform.queue.TaskQueue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Re-renders COPY_LONG when the store policy changes (spec §10.3 rule applied
 * to policy text): the deterministic POLICY_BLOCK slot is swapped using the
 * fragments already stored in content_json - no LLM call. The new version
 * keeps the old status, except that APPROVED/PUBLISHED old versions with
 * APPROVED COPY_LONG + POLICY_BLOCK templates yield an auto-approved new
 * version (actor SYSTEM, action COPY_AUTO_APPROVED_POLICY_CHANGE); a
 * PUBLISHED old version becomes STALE, an APPROVED one is archived.
 */
@Component
public class PolicyRerender implements TaskHandler {

    public static final String TYPE = "POLICY_RERENDER";

    private final TaskQueue queue;
    private final AssetMapper assetMapper;
    private final AssetService assetService;
    private final CopyAssembler assembler;
    private final TextPrecheck precheck;
    private final FactSheetService factSheetService;
    private final PolicyService policyService;
    private final ProductCatalog productCatalog;
    private final TemplateRegistry templates;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;

    public PolicyRerender(TaskQueue queue, AssetMapper assetMapper, AssetService assetService,
            CopyAssembler assembler, TextPrecheck precheck, FactSheetService factSheetService,
            PolicyService policyService, ProductCatalog productCatalog,
            TemplateRegistry templates, AuditLog auditLog, ObjectMapper objectMapper) {
        this.queue = queue;
        this.assetMapper = assetMapper;
        this.assetService = assetService;
        this.assembler = assembler;
        this.precheck = precheck;
        this.factSheetService = factSheetService;
        this.policyService = policyService;
        this.productCatalog = productCatalog;
        this.templates = templates;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
    }

    /** Queue a deduplicated re-render once the policy save transaction commits. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPolicyChanged(PolicyChangedEvent event) {
        queue.enqueue(event.tenantId(), TYPE, Map.of("version", event.version()),
                "policy-rerender");
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object handle(TaskContext ctx) {
        long tenantId = ctx.tenantId();
        int version = ctx.payload().path("version").asInt();
        List<AssetEntity> longs = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getSpecCode, "COPY_LONG")
                .in(AssetEntity::getStatus, AssetStatus.IN_REVIEW.name(),
                        AssetStatus.APPROVED.name(), AssetStatus.PUBLISHED.name()));
        int rerendered = 0;
        for (AssetEntity asset : longs) {
            if (asset.getContentJson() == null) {
                continue;
            }
            JsonNode content = objectMapper.readTree(asset.getContentJson());
            int stored = content.path("policyVersion").isMissingNode()
                    || content.path("policyVersion").isNull() ? -1
                            : content.path("policyVersion").asInt();
            if (stored == version) {
                continue;
            }
            rerender(tenantId, asset, content);
            rerendered++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rerendered", rerendered);
        result.put("policyVersion", version);
        return result;
    }

    private void rerender(long tenantId, AssetEntity asset, JsonNode content) {
        long productId = asset.getProductId();
        FactSheetView locked = factSheetService.locked(tenantId, productId)
                .orElseThrow(() -> new NonRetryableTaskException(
                        "NO_LOCKED_FACTS: " + productId));
        Optional<ProductView> product = productCatalog.findById(tenantId, productId);
        if (product.isEmpty()) {
            throw new NonRetryableTaskException("NOT_FOUND: product " + productId);
        }
        int factVersion = content.path("factVersion").asInt(0);
        Map<String, Object> contentMap = objectMapper.convertValue(content, Map.class);
        String policyHtml = null;
        Integer policyVersion = null;
        PolicyService.PolicyView current = policyService.current(tenantId).orElse(null);
        if (current != null && current.complete()) {
            policyHtml = policyService.renderBlock(tenantId);
            policyVersion = current.version();
        }
        FactsJson facts = locked.facts();
        CopyAssembler.TextAsset rerendered = assembler.rerenderLong(tenantId, contentMap, facts,
                product.get().name(), policyHtml, policyVersion);
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("policyRerender", true);
        provenance.put("fromAssetId", asset.getId());
        provenance.put("policyVersion", policyVersion);
        provenance.put("factVersion", factVersion);
        PrecheckResult check = precheck.check("COPY_LONG", rerendered.textBody(), facts);
        AssetService.AssetView created = assetService.createText(tenantId, productId,
                "COPY_LONG", asset.getVariant(), rerendered, factVersion, provenance, check);

        String oldStatus = asset.getStatus();
        if (AssetStatus.IN_REVIEW.name().equals(oldStatus)) {
            return; // createText keeps IN_REVIEW and archives the superseded one
        }
        boolean templatesApproved = templates.latestApproved(tenantId, "COPY_LONG").isPresent()
                && templates.latestApproved(tenantId, "POLICY_BLOCK").isPresent();
        if (!templatesApproved) {
            return;
        }
        AssetEntity fresh = assetMapper.selectById(created.id());
        fresh.setStatus(AssetStatus.APPROVED.name());
        assetMapper.updateById(fresh);
        auditLog.record(new AuditEntry(tenantId, ActorType.SYSTEM, "SYSTEM",
                "COPY_AUTO_APPROVED_POLICY_CHANGE", "asset", String.valueOf(created.id()),
                Map.of("specCode", "COPY_LONG", "reason", "POLICY_CHANGED",
                        "fromVersion", asset.getVersion()),
                Map.of("version", created.version(), "policyVersion", policyVersion),
                null, "COPY"));
        if (AssetStatus.PUBLISHED.name().equals(oldStatus)) {
            asset.setStatus(AssetStatus.STALE.name());
        } else {
            asset.setStatus(AssetStatus.ARCHIVED.name());
        }
        assetMapper.updateById(asset);
    }
}