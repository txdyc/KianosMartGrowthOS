package com.kiano.content.shots;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.ContentTier;
import com.kiano.content.media.MediaKind;
import com.kiano.content.media.SourceMediaEntity;
import com.kiano.content.media.SourceMediaMapper;
import com.kiano.content.profile.ProductProfileService;
import com.kiano.content.qc.QcReason;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-side view of the shooting progress: per-product checklist states
 * (with optional presigned URLs), product summaries with counts, and the
 * reshoot list. The "current" media of a shot is the newest row that is not
 * SUPERSEDED; ACCEPTED maps to OK, RESHOOT to RESHOOT, no row to MISSING.
 */
@Service
public class ShotStatusService {

    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    private final ProductCatalog productCatalog;
    private final ProductProfileService profileService;
    private final ShotRequirementService requirementService;
    private final SourceMediaMapper sourceMediaMapper;
    private final ObjectStorage objectStorage;
    private final ObjectMapper objectMapper;

    public ShotStatusService(ProductCatalog productCatalog, ProductProfileService profileService,
            ShotRequirementService requirementService, SourceMediaMapper sourceMediaMapper,
            ObjectStorage objectStorage, ObjectMapper objectMapper) {
        this.productCatalog = productCatalog;
        this.profileService = profileService;
        this.requirementService = requirementService;
        this.sourceMediaMapper = sourceMediaMapper;
        this.objectStorage = objectStorage;
        this.objectMapper = objectMapper;
    }

    public ProductShotStatus statusFor(long tenantId, long productId, boolean withUrls) {
        ProductView product = productCatalog.findById(tenantId, productId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Product not found"));
        ContentTier tier = profileService.tierOf(tenantId, productId);
        List<ShotStatusLine> lines = buildLines(tier, product,
                currentMedia(tenantId, productId), withUrls);
        boolean complete = lines.stream().filter(ShotStatusLine::required)
                .allMatch(line -> line.state() == ShotState.OK);
        return new ProductShotStatus(product.id(), product.sku(), product.name(), tier, lines, complete);
    }

    public List<ContentProductSummary> summaries(long tenantId, ContentTier tierOrNull, String qOrNull) {
        Map<Long, ContentTier> tiers = profileService.tiersFor(tenantId);
        Map<Long, Map<String, SourceMediaEntity>> mediaByProduct = currentMediaByProduct(tenantId);
        String query = qOrNull == null || qOrNull.isBlank() ? null
                : qOrNull.toLowerCase(Locale.ROOT);

        List<ContentProductSummary> summaries = new ArrayList<>();
        for (ProductView product : productCatalog.listTopLevel(tenantId)) {
            ContentTier tier = tiers.getOrDefault(product.id(), ContentTier.STANDARD);
            if (tierOrNull != null && tier != tierOrNull) {
                continue;
            }
            if (query != null && !matchesQuery(product, query)) {
                continue;
            }
            Map<String, SourceMediaEntity> media = mediaByProduct.getOrDefault(product.id(), Map.of());
            int required = 0;
            int ok = 0;
            int reshoot = 0;
            int missing = 0;
            for (ShotRequirementView requirement : requirementService.requiredFor(tier,
                    product.categorySlugs(), product.name())) {
                if (!requirement.required()) {
                    continue;
                }
                required++;
                SourceMediaEntity current = media.get(requirement.code());
                if (current == null) {
                    missing++;
                } else if ("ACCEPTED".equals(current.getStatus())) {
                    ok++;
                } else {
                    reshoot++;
                }
            }
            summaries.add(new ContentProductSummary(product.id(), product.sku(), product.name(),
                    product.type(), product.status(), product.regularPrice(), product.salePrice(),
                    product.price(), product.stockStatus(), product.imageUrl(), tier, required, ok,
                    reshoot, missing, reshoot == 0 && missing == 0));
        }
        return summaries;
    }

    public List<ReshootLine> reshootList(long tenantId, ContentTier tierOrNull) {
        Map<Long, ContentTier> tiers = profileService.tiersFor(tenantId);
        Map<Long, Map<String, SourceMediaEntity>> mediaByProduct = currentMediaByProduct(tenantId);

        List<ProductView> products = new ArrayList<>(productCatalog.listTopLevel(tenantId));
        products.removeIf(product -> {
            ContentTier tier = tiers.getOrDefault(product.id(), ContentTier.STANDARD);
            return tierOrNull != null && tier != tierOrNull;
        });
        products.sort(Comparator
                .comparingInt((ProductView product) ->
                        tiers.getOrDefault(product.id(), ContentTier.STANDARD) == ContentTier.HERO ? 0 : 1)
                .thenComparing(ProductView::sku));

        List<ReshootLine> reshootLines = new ArrayList<>();
        for (ProductView product : products) {
            ContentTier tier = tiers.getOrDefault(product.id(), ContentTier.STANDARD);
            Map<String, SourceMediaEntity> media = mediaByProduct.getOrDefault(product.id(), Map.of());
            for (ShotStatusLine line : buildLines(tier, product, media, false)) {
                if (line.state() == ShotState.OK) {
                    continue;
                }
                reshootLines.add(new ReshootLine(product.sku(), product.name(), tier, line.code(),
                        line.state(), line.reasons(), line.guidanceEn(), line.guidanceZh()));
            }
        }
        return reshootLines;
    }

    /**
     * Checklist lines: the requirements for the tier (with category / product
     * name guidance overrides), plus any extra current media codes not in the
     * checklist (e.g. PROMO) as non-required lines.
     */
    private List<ShotStatusLine> buildLines(ContentTier tier, ProductView product,
            Map<String, SourceMediaEntity> currentMedia, boolean withUrls) {
        List<ShotStatusLine> lines = new ArrayList<>();
        List<String> checklistCodes = new ArrayList<>();
        for (ShotRequirementView requirement : requirementService.requiredFor(tier,
                product.categorySlugs(), product.name())) {
            checklistCodes.add(requirement.code());
            lines.add(toLine(requirement.code(), requirement.kind(), requirement.required(),
                    currentMedia.get(requirement.code()), requirement.guidanceEn(),
                    requirement.guidanceZh(), withUrls));
        }
        currentMedia.keySet().stream()
                .filter(code -> !checklistCodes.contains(code))
                .sorted()
                .forEach(code -> {
                    SourceMediaEntity media = currentMedia.get(code);
                    lines.add(toLine(code, MediaKind.valueOf(media.getKind()), false, media, null,
                            null, withUrls));
                });
        return lines;
    }

    private ShotStatusLine toLine(String code, MediaKind kind, boolean required, SourceMediaEntity media,
            String guidanceEn, String guidanceZh, boolean withUrls) {
        if (media == null) {
            return new ShotStatusLine(code, kind, required, ShotState.MISSING, null, List.of(),
                    guidanceEn, guidanceZh, null, null);
        }
        ShotState state = "ACCEPTED".equals(media.getStatus()) ? ShotState.OK : ShotState.RESHOOT;
        String thumbUrl = null;
        String mediaUrl = null;
        if (withUrls) {
            mediaUrl = objectStorage.presignGet(media.getObjectKey(), PRESIGN_TTL).toString();
            if (media.getThumbObjectKey() != null) {
                thumbUrl = objectStorage.presignGet(media.getThumbObjectKey(), PRESIGN_TTL).toString();
            }
        }
        return new ShotStatusLine(code, kind, required, state, media.getId(), reasonsOf(media),
                guidanceEn, guidanceZh, thumbUrl, mediaUrl);
    }

    private List<QcReason> reasonsOf(SourceMediaEntity media) {
        if (media.getQcJson() == null || media.getQcJson().isBlank()) {
            return List.of();
        }
        JsonNode reasons = objectMapper.readTree(media.getQcJson()).path("reasons");
        if (!reasons.isArray()) {
            return List.of();
        }
        List<QcReason> parsed = new ArrayList<>();
        reasons.forEach(reason -> parsed.add(QcReason.valueOf(reason.asText())));
        return parsed;
    }

    private Map<String, SourceMediaEntity> currentMedia(long tenantId, long productId) {
        Map<String, SourceMediaEntity> byCode = new HashMap<>();
        for (SourceMediaEntity row : sourceMediaMapper.selectList(
                Wrappers.<SourceMediaEntity>lambdaQuery()
                        .eq(SourceMediaEntity::getTenantId, tenantId)
                        .eq(SourceMediaEntity::getProductId, productId)
                        .ne(SourceMediaEntity::getStatus, "SUPERSEDED")
                        .orderByAsc(SourceMediaEntity::getId))) {
            byCode.put(row.getShotCode(), row);
        }
        return byCode;
    }

    private Map<Long, Map<String, SourceMediaEntity>> currentMediaByProduct(long tenantId) {
        Map<Long, Map<String, SourceMediaEntity>> byProduct = new HashMap<>();
        for (SourceMediaEntity row : sourceMediaMapper.selectList(
                Wrappers.<SourceMediaEntity>lambdaQuery()
                        .eq(SourceMediaEntity::getTenantId, tenantId)
                        .ne(SourceMediaEntity::getStatus, "SUPERSEDED")
                        .orderByAsc(SourceMediaEntity::getId))) {
            byProduct.computeIfAbsent(row.getProductId(), id -> new HashMap<>())
                    .put(row.getShotCode(), row);
        }
        return byProduct;
    }

    private static boolean matchesQuery(ProductView product, String lowerCaseQuery) {
        return (product.sku() != null
                && product.sku().toLowerCase(Locale.ROOT).contains(lowerCaseQuery))
                || (product.name() != null
                        && product.name().toLowerCase(Locale.ROOT).contains(lowerCaseQuery));
    }
}
