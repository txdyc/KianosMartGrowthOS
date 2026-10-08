package com.kiano.content.derive;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetService;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactSheetService.FactSheetView;
import com.kiano.content.template.TemplateRenderer;
import com.kiano.content.template.TemplateRegistry;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import com.kiano.platform.queue.TaskHandler;
import com.kiano.platform.storage.ObjectStorage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Queue handler for TEMPLATE_RENDER: renders PAGE_SPEC (from the locked
 * facts) or PAGE_INFO (facts + the approved PAGE_MAIN photo) at 1600×1600
 * and saves the JPEG as an IN_REVIEW asset with its fact version.
 */
@Component
public class TemplateRenderTaskHandler implements TaskHandler {

    public static final String TYPE = "TEMPLATE_RENDER";
    private static final int SIZE = 1600;

    private final FactSheetService factSheetService;
    private final ProductCatalog productCatalog;
    private final TemplateRenderer renderer;
    private final TemplateRegistry templates;
    private final AssetService assetService;
    private final AssetMapper assetMapper;
    private final ObjectStorage storage;

    public TemplateRenderTaskHandler(FactSheetService factSheetService,
            ProductCatalog productCatalog, TemplateRenderer renderer, TemplateRegistry templates,
            AssetService assetService, AssetMapper assetMapper, ObjectStorage storage) {
        this.factSheetService = factSheetService;
        this.productCatalog = productCatalog;
        this.renderer = renderer;
        this.templates = templates;
        this.assetService = assetService;
        this.assetMapper = assetMapper;
        this.storage = storage;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object handle(TaskContext ctx) {
        long tenantId = ctx.tenantId();
        long productId = ctx.payload().path("productId").asLong();
        int factVersion = ctx.payload().path("factVersion").asInt(0);
        String spec = ctx.payload().path("spec").asString();
        FactSheetView locked = factSheetService.locked(tenantId, productId)
                .orElseThrow(() -> new NonRetryableTaskException(
                        "NO_LOCKED_FACTS: facts must be locked before template renders"));
        if (locked.version() != factVersion) {
            return Map.of("skipped", "STALE_FACTS");
        }
        Optional<ProductView> product = productCatalog.findById(tenantId, productId);
        if (product.isEmpty()) {
            throw new NonRetryableTaskException("NOT_FOUND: product " + productId + " not found");
        }
        byte[] rendered;
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("template", Map.of("code", spec, "version",
                templateVersion(tenantId, spec)));
        provenance.put("factVersion", locked.version());
        if ("PAGE_SPEC".equals(spec)) {
            rendered = renderer.render(tenantId, "PAGE_SPEC",
                    renderer.specModel(locked.facts().toTemplateFacts(product.get().name())),
                    SIZE, SIZE);
        } else if ("PAGE_INFO".equals(spec)) {
            AssetEntity main = approvedMain(tenantId, productId);
            byte[] mainJpeg = storage.download(main.getObjectKey());
            byte[] mainPng = ImageCodec.png(ImageCodec.read(mainJpeg));
            rendered = renderer.render(tenantId, "PAGE_INFO",
                    renderer.infoModel(locked.facts().toTemplateFacts(product.get().name()),
                            mainPng),
                    SIZE, SIZE);
            provenance.put("mainAssetId", main.getId());
        } else {
            throw new NonRetryableTaskException(
                    "BAD_SPEC: TEMPLATE_RENDER only renders PAGE_SPEC or PAGE_INFO");
        }
        assetService.createImageFromBytes(tenantId, productId, spec, "default", rendered,
                locked.version(), provenance, product.get().sku());
        return Map.of("spec", spec, "factVersion", locked.version());
    }

    private int templateVersion(long tenantId, String code) {
        return templates.latestApproved(tenantId, code)
                .orElseThrow(() -> new NonRetryableTaskException(
                        "TEMPLATE_NOT_FOUND: no approved template for " + code))
                .getVersion();
    }

    private @Nullable AssetEntity approvedMain(long tenantId, long productId) {
        List<AssetEntity> mains = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "PAGE_MAIN")
                .eq(AssetEntity::getStatus, AssetStatus.APPROVED.name())
                .orderByDesc(AssetEntity::getVersion)
                .last("limit 1"));
        if (mains.isEmpty()) {
            throw new NonRetryableTaskException(
                    "NO_MAIN_IMAGE: PAGE_INFO needs an approved PAGE_MAIN asset");
        }
        return mains.get(0);
    }
}