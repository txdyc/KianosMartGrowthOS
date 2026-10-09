package com.kiano.content.ads;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.ContentTier;
import com.kiano.content.ads.AdBaseSelector.BaseImage;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.asset.AssetService;
import com.kiano.content.asset.PrecheckFlag;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactSheetService.FactSheetView;
import com.kiano.content.media.SourceMediaEntity;
import com.kiano.content.media.SourceMediaMapper;
import com.kiano.content.policy.PolicySection;
import com.kiano.content.policy.PolicyService;
import com.kiano.content.profile.ProductProfileService;
import com.kiano.content.template.TemplateRegistry;
import com.kiano.content.template.TemplateRenderer;
import com.kiano.content.template.TemplateRenderer.Rect;
import com.kiano.platform.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders the twelve AD_STATIC assets (4 hooks x 3 sizes) from approved
 * AD_COPY text and approved bases (§7.3). Before rendering it enforces the
 * C4a preconditions (409 AD_PRECONDITIONS with the missing items): HERO tier,
 * locked facts, one approved AD_COPY per hook, the store badges, an approved
 * PAGE_MAIN and PAGE_SCENE and an ACCEPTED V1 video. The 9:16 safe area is
 * enforced by shrinking the font up to two steps; text that still escapes
 * y[269, 1248] is saved with the TEXT_OUTSIDE_SAFE_AREA precheck flag.
 * {@code priceOnly} (used by the price-change re-render, Task 8) skips the
 * preconditions and reports per-variant failures as skipped instead.
 */
@Service
public class AdRenderService {

    private static final double[] FONT_SCALES = {1.0, 0.9, 0.8};
    private static final double NINE_SIXTEEN_TOP_SAFE = 269;
    private static final double NINE_SIXTEEN_BOTTOM_SAFE = 1248;

    /** One static: its hook, size and the {@code hook-wxh} variant key. */
    public record AdVariant(AdHook hook, AdSize size, String variant) {
        public static AdVariant of(AdHook hook, AdSize size) {
            return new AdVariant(hook, size, hook.wire() + "-" + size.label());
        }
    }

    /** Which variants were created and which failed (priceOnly) and why. */
    public record RenderOutcome(List<String> rendered, Map<String, String> skipped) {
    }

    private final ProductCatalog productCatalog;
    private final ProductProfileService profileService;
    private final FactSheetService factSheetService;
    private final TemplateRenderer renderer;
    private final TemplateRegistry templates;
    private final AssetMapper assetMapper;
    private final AssetService assetService;
    private final AdBaseSelector baseSelector;
    private final AdModelBuilder modelBuilder;
    private final PolicyService policyService;
    private final SourceMediaMapper sourceMediaMapper;
    private final ObjectMapper objectMapper;

    public AdRenderService(ProductCatalog productCatalog, ProductProfileService profileService,
            FactSheetService factSheetService, TemplateRenderer renderer,
            TemplateRegistry templates, AssetMapper assetMapper, AssetService assetService,
            AdBaseSelector baseSelector, AdModelBuilder modelBuilder, PolicyService policyService,
            SourceMediaMapper sourceMediaMapper, ObjectMapper objectMapper) {
        this.productCatalog = productCatalog;
        this.profileService = profileService;
        this.factSheetService = factSheetService;
        this.renderer = renderer;
        this.templates = templates;
        this.assetMapper = assetMapper;
        this.assetService = assetService;
        this.baseSelector = baseSelector;
        this.modelBuilder = modelBuilder;
        this.policyService = policyService;
        this.sourceMediaMapper = sourceMediaMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * All twelve variants in the fixed export order: hook then size.
     */
    public List<AdVariant> allVariants() {
        List<AdVariant> all = new ArrayList<>();
        for (AdHook hook : AdHook.values()) {
            for (AdSize size : AdSize.values()) {
                all.add(AdVariant.of(hook, size));
            }
        }
        return all;
    }

    /**
     * The queue handler's entry point: renders the requested (or all) variants.
     * {@code priceOnly} mode skips the preconditions; missing copy or bases then
     * fail that variant only.
     */
    public RenderOutcome renderAll(long tenantId, long productId,
            @Nullable List<String> variantKeys, int frameCandidate, boolean priceOnly) {
        checkPreconditions(tenantId, productId, priceOnly);
        ProductView product = productCatalog.findById(tenantId, productId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Product not found"));
        FactSheetView locked = factSheetService.locked(tenantId, productId).orElse(null);
        int factVersion = locked == null ? 0 : locked.version();
        String warranty = locked == null ? null : locked.facts().warranty();
        PriceDisplay price = PriceDisplay.of(product, Instant.now());
        List<AdVariant> targets = variantKeys == null || variantKeys.isEmpty()
                ? allVariants() : parse(variantKeys);
        List<String> rendered = new ArrayList<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        for (AdVariant target : targets) {
            try {
                renderVariant(tenantId, product, target, frameCandidate, factVersion, warranty,
                        price);
                rendered.add(target.variant());
            } catch (ApiException ex) {
                skipped.put(target.variant(), ex.getCode());
            }
        }
        return new RenderOutcome(List.copyOf(rendered), Map.copyOf(skipped));
    }

    /** Every C4a precondition in one 409 AD_PRECONDITIONS listing what is missing. */
    public void checkPreconditions(long tenantId, long productId, boolean priceOnly) {
        if (priceOnly) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (profileService.tierOf(tenantId, productId) != ContentTier.HERO) {
            missing.add("NOT_HERO");
        }
        if (factSheetService.locked(tenantId, productId).isEmpty()) {
            missing.add("FACTS_NOT_LOCKED");
        }
        for (AdHook hook : AdHook.values()) {
            if (approvedAdCopy(tenantId, productId, hook).isEmpty()) {
                missing.add("AD_COPY:" + hook.wire());
            }
        }
        if (latestApproved(tenantId, productId, "PAGE_MAIN").isEmpty()) {
            missing.add("PAGE_MAIN");
        }
        if (approved(tenantId, productId, "PAGE_SCENE").isEmpty()) {
            missing.add("PAGE_SCENE");
        }
        if (!hasAcceptedV1(tenantId, productId)) {
            missing.add("V1");
        }
        try {
            policyService.requireAdBadges(tenantId);
        } catch (ApiException ex) {
            if ("POLICY_BADGES_MISSING".equals(ex.getCode())) {
                missing.add("POLICY_BADGES_MISSING");
            } else {
                throw ex;
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "AD_PRECONDITIONS",
                    "Ad render preconditions are not met",
                    Map.of("missing", missing));
        }
    }

    private void renderVariant(long tenantId, ProductView product, AdVariant target,
            int frameCandidate, int factVersion, @Nullable String warranty, PriceDisplay price) {
        AdHook hook = target.hook();
        AdSize size = target.size();
        String code = templateCode(hook);
        AssetEntity adCopy = approvedAdCopy(tenantId, product.id(), hook)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "AD_COPY_MISSING",
                        "No approved AD_COPY asset for hook " + hook.wire(),
                        Map.of("hook", hook.wire())));
        AdCopyText copy = AdCopyText.fromJson(adCopy.getTextBody());
        BaseImage base = baseSelector.select(tenantId, product.id(), hook, frameCandidate);
        Map<PolicySection, String> badges = hook == AdHook.TRUST
                ? policyService.requireAdBadges(tenantId) : null;

        // Safe area: full font first, then two shrinking steps, else flag.
        double scale = 1.0;
        Map<String, Object> model = modelBuilder.build(product, hook, size, base, copy, price,
                badges, warranty, scale);
        if (size == AdSize.S9X16 && !withinSafeArea(tenantId, code, model, size)) {
            scale = 0.9;
            model = modelBuilder.build(product, hook, size, base, copy, price, badges, warranty,
                    scale);
            if (!withinSafeArea(tenantId, code, model, size)) {
                scale = 0.8;
                model = modelBuilder.build(product, hook, size, base, copy, price, badges,
                        warranty, scale);
            }
        }
        List<PrecheckFlag> flags = new ArrayList<>();
        if (size == AdSize.S9X16 && !withinSafeArea(tenantId, code, model, size)) {
            flags.add(PrecheckFlag.TEXT_OUTSIDE_SAFE_AREA);
        }
        byte[] png = renderer.render(tenantId, code, model, size.width(), size.height());

        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("template", Map.of("code", code,
                "version", templateVersion(tenantId, code)));
        provenance.put("adCopyAssetId", adCopy.getId());
        provenance.put("baseAssetId", base.assetId());
        provenance.put("sourceMediaId", base.sourceMediaId());
        provenance.put("frameTime", base.frameTime());
        Map<String, Object> priceInfo = new LinkedHashMap<>();
        priceInfo.put("current", price.current());
        priceInfo.put("strike", price.strike());
        priceInfo.put("endsLabel", price.endsLabel());
        priceInfo.put("snapshot", price.snapshot());
        provenance.put("price", priceInfo);
        provenance.put("factVersion", factVersion);
        provenance.put("hook", hook.wire());

        boolean dependsOnPrice = hook == AdHook.PRICEHOOK;
        AdRenderMeta meta = new AdRenderMeta(dependsOnPrice,
                dependsOnPrice ? price.snapshot() : null, base.sourceType());
        var view = assetService.createImageFromBytes(tenantId, product.id(), "AD_STATIC",
                target.variant(), png, factVersion, provenance, product.sku(), meta);
        if (!flags.isEmpty()) {
            assetMapper.update(null, new LambdaUpdateWrapper<AssetEntity>()
                    .eq(AssetEntity::getId, view.id())
                    .set(AssetEntity::getPrecheckJson, objectMapper.writeValueAsString(Map.of(
                            "flags", flags.stream().map(Enum::name).toList(),
                            "metrics", Map.of()))));
        }
    }

    /** 9:16 text must stay inside y [269, 1248]; other sizes have no constraint. */
    private boolean withinSafeArea(long tenantId, String code, Map<String, Object> model,
            AdSize size) {
        List<Rect> boxes = renderer.textBoxes(tenantId, code, model, size.width(), size.height());
        for (Rect box : boxes) {
            if (box.top() < NINE_SIXTEEN_TOP_SAFE || box.bottom() > NINE_SIXTEEN_BOTTOM_SAFE) {
                return false;
            }
        }
        return true;
    }

    private List<AdVariant> parse(List<String> variantKeys) {
        List<AdVariant> variants = new ArrayList<>();
        for (String key : variantKeys) {
            int dash = key.indexOf('-');
            if (dash <= 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                        "Unknown ad variant " + key);
            }
            AdHook hook;
            AdSize size;
            try {
                hook = AdHook.fromWire(key.substring(0, dash));
                size = sizeByLabel(key.substring(dash + 1));
            } catch (RuntimeException ex) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                        "Unknown ad variant " + key);
            }
            variants.add(AdVariant.of(hook, size));
        }
        return variants;
    }

    private static AdSize sizeByLabel(String label) {
        for (AdSize size : AdSize.values()) {
            if (size.label().equals(label)) {
                return size;
            }
        }
        throw new IllegalArgumentException("Unknown ad size " + label);
    }

    private static String templateCode(AdHook hook) {
        return "AD_" + hook.name();
    }

    private int templateVersion(long tenantId, String code) {
        return templates.latestApproved(tenantId, code)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND",
                        "No approved template for " + code)).getVersion();
    }

    private Optional<AssetEntity> approvedAdCopy(long tenantId, long productId, AdHook hook) {
        List<AssetEntity> rows = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "AD_COPY")
                .eq(AssetEntity::getVariant, hook.wire())
                .eq(AssetEntity::getStatus, AssetStatus.APPROVED.name())
                .orderByDesc(AssetEntity::getVersion)
                .last("limit 1"));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Optional<AssetEntity> latestApproved(long tenantId, long productId, String specCode) {
        List<AssetEntity> rows = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, specCode)
                .eq(AssetEntity::getStatus, AssetStatus.APPROVED.name())
                .orderByDesc(AssetEntity::getVersion)
                .last("limit 1"));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private List<AssetEntity> approved(long tenantId, long productId, String specCode) {
        return assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, specCode)
                .eq(AssetEntity::getStatus, AssetStatus.APPROVED.name())
                .orderByDesc(AssetEntity::getVersion));
    }

    private boolean hasAcceptedV1(long tenantId, long productId) {
        List<SourceMediaEntity> rows = sourceMediaMapper.selectList(
                Wrappers.<SourceMediaEntity>lambdaQuery()
                        .eq(SourceMediaEntity::getTenantId, tenantId)
                        .eq(SourceMediaEntity::getProductId, productId)
                        .eq(SourceMediaEntity::getShotCode, "V1")
                        .eq(SourceMediaEntity::getKind, "VIDEO")
                        .eq(SourceMediaEntity::getStatus, "ACCEPTED")
                        .last("limit 1"));
        return !rows.isEmpty();
    }
}
