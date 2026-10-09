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
import com.kiano.content.template.TemplateEntity;
import com.kiano.content.template.TemplateRegistry;
import com.kiano.content.template.TemplateRenderer;
import com.kiano.content.template.TemplateRenderer.Rect;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
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
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;

    public AdRenderService(ProductCatalog productCatalog, ProductProfileService profileService,
            FactSheetService factSheetService, TemplateRenderer renderer,
            TemplateRegistry templates, AssetMapper assetMapper, AssetService assetService,
            AdBaseSelector baseSelector, AdModelBuilder modelBuilder, PolicyService policyService,
            SourceMediaMapper sourceMediaMapper, AuditLog auditLog, ObjectMapper objectMapper) {
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
        this.auditLog = auditLog;
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
        return renderAll(tenantId, productId, variantKeys, frameCandidate, priceOnly, null);
    }

    /**
     * Price-only re-render (called after a price change): when
     * {@code autoApproveFrom} maps a variant to its previous asset id, the new
     * render is auto-approved if only the price changed - the template code and
     * version, the AD_COPY asset and the base (source media + frame for demo)
     * are all the same and the template is still APPROVED. Missing copy or base
     * then fails that variant only (its previous version stays STALE).
     * Price-only runs are idempotent: a variant whose latest version already
     * shows the current price is skipped as UP_TO_DATE, and any APPROVED version
     * showing another price is marked STALE first (the newest STALE version
     * then becomes the auto-approval baseline), so a follow-up task after a
     * burst of price changes converges on the latest price.
     */
    public RenderOutcome renderAll(long tenantId, long productId,
            @Nullable List<String> variantKeys, int frameCandidate, boolean priceOnly,
            @Nullable Map<String, Long> autoApproveFrom) {
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
        // one base per hook for the whole run: the three sizes share it (the demo
        // base costs a V1 download plus three ffmpeg extractions)
        Map<AdHook, BaseImage> bases = new EnumMap<>(AdHook.class);
        Map<AdHook, ApiException> baseFailures = new EnumMap<>(AdHook.class);
        for (AdVariant target : targets) {
            try {
                Long baseline = null;
                if (priceOnly) {
                    if (isUpToDate(tenantId, productId, target.variant(), price)) {
                        skipped.put(target.variant(), "UP_TO_DATE");
                        continue;
                    }
                    baseline = staleOutdatedPrices(tenantId, productId, target.variant(), price,
                            autoApproveFrom == null ? null : autoApproveFrom.get(target.variant()));
                }
                BaseImage base = baseFor(tenantId, productId, target.hook(), frameCandidate,
                        bases, baseFailures);
                renderVariant(tenantId, product, target, base, frameCandidate, factVersion,
                        warranty, price, baseline);
                rendered.add(target.variant());
            } catch (ApiException ex) {
                skipped.put(target.variant(), ex.getCode());
            }
        }
        return new RenderOutcome(List.copyOf(rendered), Map.copyOf(skipped));
    }

    private BaseImage baseFor(long tenantId, long productId, AdHook hook, int frameCandidate,
            Map<AdHook, BaseImage> bases, Map<AdHook, ApiException> failures) {
        ApiException failure = failures.get(hook);
        if (failure != null) {
            throw failure;
        }
        BaseImage cached = bases.get(hook);
        if (cached != null) {
            return cached;
        }
        try {
            BaseImage base = baseSelector.select(tenantId, productId, hook, frameCandidate);
            bases.put(hook, base);
            return base;
        } catch (ApiException ex) {
            failures.put(hook, ex);
            throw ex;
        }
    }

    /**
     * Price-only re-render guard: the variant's latest version already shows the
     * current price (a follow-up task after a burst of price changes) - nothing
     * to do.
     */
    private boolean isUpToDate(long tenantId, long productId, String variant, PriceDisplay price) {
        AssetEntity latest = assetMapper.selectOne(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "AD_STATIC")
                .eq(AssetEntity::getVariant, variant)
                .orderByDesc(AssetEntity::getVersion)
                .last("limit 1"));
        return latest != null && Boolean.TRUE.equals(latest.getDependsOnPrice())
                && List.of(AssetStatus.APPROVED.name(), AssetStatus.IN_REVIEW.name(),
                        AssetStatus.PUBLISHED.name()).contains(latest.getStatus())
                && showsPrice(latest, price);
    }

    /**
     * Marks every APPROVED/PUBLISHED price-dependent version of the variant that
     * shows another price STALE (a version auto-approved by an earlier re-render
     * may already be outdated again) and returns the auto-approval baseline: the
     * newest STALE version, or the listener's id when that is newer.
     */
    private @Nullable Long staleOutdatedPrices(long tenantId, long productId, String variant,
            PriceDisplay price, @Nullable Long listenerBaseline) {
        List<AssetEntity> versions = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "AD_STATIC")
                .eq(AssetEntity::getVariant, variant)
                .orderByDesc(AssetEntity::getVersion));
        AssetEntity newestStale = null;
        for (AssetEntity version : versions) {
            boolean live = AssetStatus.APPROVED.name().equals(version.getStatus())
                    || AssetStatus.PUBLISHED.name().equals(version.getStatus());
            if (live && Boolean.TRUE.equals(version.getDependsOnPrice())
                    && !showsPrice(version, price)) {
                version.setStatus(AssetStatus.STALE.name());
                assetMapper.updateById(version);
            }
            if (newestStale == null && AssetStatus.STALE.name().equals(version.getStatus())) {
                newestStale = version;
            }
        }
        if (listenerBaseline == null) {
            return newestStale == null ? null : newestStale.getId();
        }
        if (newestStale == null) {
            return listenerBaseline;
        }
        AssetEntity listenerAsset = assetMapper.selectById(listenerBaseline);
        return listenerAsset != null && listenerAsset.getVersion() > newestStale.getVersion()
                ? listenerBaseline : newestStale.getId();
    }

    /** Whether the asset was rendered with exactly this price, strike and end label. */
    private boolean showsPrice(AssetEntity asset, PriceDisplay price) {
        JsonNode rendered = asset.getProvenanceJson() == null ? null
                : readProvenance(asset).path("price");
        if (rendered == null) {
            rendered = objectMapper.nullNode();
        }
        if (!rendered.isObject()) {
            return asset.getPriceSnapshot() != null
                    && asset.getPriceSnapshot().compareTo(price.snapshot()) == 0;
        }
        return Objects.equals(textOrNull(rendered, "current"), price.current())
                && Objects.equals(textOrNull(rendered, "strike"), price.strike())
                && Objects.equals(textOrNull(rendered, "endsLabel"), price.endsLabel());
    }

    private static @Nullable String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
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
        if (baseSelector.eligibleBases(tenantId, productId, "PAGE_MAIN").isEmpty()) {
            missing.add("PAGE_MAIN");
        }
        if (baseSelector.eligibleBases(tenantId, productId, "PAGE_SCENE").isEmpty()) {
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
            BaseImage base, int frameCandidate, int factVersion, @Nullable String warranty,
            PriceDisplay price, @Nullable Long autoApproveBaseline) {
        AdHook hook = target.hook();
        AdSize size = target.size();
        String code = templateCode(hook);
        AssetEntity adCopy = approvedAdCopy(tenantId, product.id(), hook)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "AD_COPY_MISSING",
                        "No approved AD_COPY asset for hook " + hook.wire(),
                        Map.of("hook", hook.wire())));
        AdCopyText copy = AdCopyText.fromJson(adCopy.getTextBody());
        Map<PolicySection, String> badges = hook == AdHook.TRUST
                ? policyService.requireAdBadges(tenantId) : null;

        // Safe area (9:16 only): full font first, then two shrinking steps, else
        // flag. Each step probes once; the last probe's verdict is reused.
        double[] scales = size == AdSize.S9X16 ? FONT_SCALES : new double[] {1.0};
        Map<String, Object> model = null;
        boolean inside = true;
        for (double scale : scales) {
            model = modelBuilder.build(product, hook, size, base, copy, price, badges, warranty,
                    scale);
            if (size != AdSize.S9X16) {
                break;
            }
            inside = withinSafeArea(tenantId, code, model, size);
            if (inside) {
                break;
            }
        }
        List<PrecheckFlag> flags = new ArrayList<>();
        if (!inside) {
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
        if (hook == AdHook.DEMO) {
            // regenerate advances from this without re-extracting frames
            provenance.put("frameCandidate", frameCandidate);
        }
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
        maybeAutoApprove(tenantId, target, view.id(), provenance, price, autoApproveBaseline);
    }

    /**
     * Task 8 auto-approval: only when the previous render (the STALE asset from
     * the price-change listener) used the same template code/version, the same
     * AD_COPY asset and the same base - for demo that means the same V1 source
     * media and frame time - and that template version is still APPROVED. In
     * every other case the new version stays IN_REVIEW for a human.
     */
    private void maybeAutoApprove(long tenantId, AdVariant target, long newAssetId,
            Map<String, Object> provenance, PriceDisplay price, @Nullable Long oldId) {
        if (oldId == null) {
            return;
        }
        AssetEntity old = assetMapper.selectById(oldId);
        if (old == null) {
            return;
        }
        String code = templateCode(target.hook());
        JsonNode oldProv = readProvenance(old);
        JsonNode newProv = objectMapper.valueToTree(provenance);
        if (!sameField(oldProv, newProv, "template", "code")
                || oldProv.path("template").path("version").asInt()
                        != newProv.path("template").path("version").asInt()) {
            return;
        }
        // the template version must still be the APPROVED one
        Optional<TemplateEntity> approved = templates.latestApproved(tenantId, code);
        if (approved.isEmpty() || approved.get().getVersion()
                != newProv.path("template").path("version").asInt()) {
            return;
        }
        if (oldProv.path("adCopyAssetId").asLong() != newProv.path("adCopyAssetId").asLong()) {
            return;
        }
        boolean sameBase = target.hook() == AdHook.DEMO
                ? oldProv.path("sourceMediaId").asLong() == newProv.path("sourceMediaId").asLong()
                        && oldProv.path("frameTime").asDouble()
                                == newProv.path("frameTime").asDouble()
                : oldProv.path("baseAssetId").asLong() == newProv.path("baseAssetId").asLong();
        if (!sameBase) {
            return;
        }
        assetMapper.update(null, new LambdaUpdateWrapper<AssetEntity>()
                .eq(AssetEntity::getId, newAssetId)
                .set(AssetEntity::getStatus, AssetStatus.APPROVED.name()));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("variant", target.variant());
        before.put("priceSnapshot", old.getPriceSnapshot());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("variant", target.variant());
        after.put("priceSnapshot", price.snapshot());
        after.put("assetId", newAssetId);
        auditLog.record(new AuditEntry(tenantId, ActorType.SYSTEM, "SYSTEM",
                "AD_AUTO_APPROVED_PRICE_CHANGE", "asset", String.valueOf(newAssetId),
                before, after, null, "ADS"));
    }

    private JsonNode readProvenance(AssetEntity asset) {
        try {
            return objectMapper.readTree(asset.getProvenanceJson());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid provenance on asset "
                    + asset.getId(), ex);
        }
    }

    private static boolean sameField(JsonNode a, JsonNode b, String parent, String field) {
        return a.path(parent).path(field).asText().equals(b.path(parent).path(field).asText());
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
