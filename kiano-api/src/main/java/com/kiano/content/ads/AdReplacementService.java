package com.kiano.content.ads;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.content.asset.AssetEntity;
import com.kiano.content.asset.AssetMapper;
import com.kiano.content.asset.AssetStatus;
import com.kiano.content.publish.PublicationEntity;
import com.kiano.content.publish.PublicationMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The "assets to swap in the ad platform" list: old versions are statics that
 * were shipped in an APPLIED ad export and have since gone STALE (the price
 * changed); the replacement is the same variant's latest APPROVED version. A
 * row only appears once that replacement is APPROVED (until then it is still
 * pending review) and disappears once the replacement itself has been shipped
 * in an APPLIED export (the swap is done).
 */
@Service
public class AdReplacementService {

    /** One swap: the exported file and its approved replacement. */
    public record Replacement(long productId, @Nullable String sku, String variant,
            String oldFileName, long oldAssetId,
            String newFileName, long newAssetId, String newStatus) {
    }

    private final PublicationMapper publicationMapper;
    private final AssetMapper assetMapper;
    private final ProductCatalog productCatalog;
    private final ObjectMapper objectMapper;

    public AdReplacementService(PublicationMapper publicationMapper, AssetMapper assetMapper,
            ProductCatalog productCatalog, ObjectMapper objectMapper) {
        this.publicationMapper = publicationMapper;
        this.assetMapper = assetMapper;
        this.productCatalog = productCatalog;
        this.objectMapper = objectMapper;
    }

    public List<Replacement> list(long tenantId) {
        Map<Long, AssetEntity> exported = new LinkedHashMap<>();
        for (PublicationEntity publication : exportedPublications(tenantId)) {
            List<Long> assetIds = readIdList(publication.getAssetIds());
            for (long assetId : assetIds) {
                exported.putIfAbsent(assetId, assetMapper.selectById(assetId));
            }
        }
        List<Replacement> replacements = new ArrayList<>();
        Map<Long, String> skus = new LinkedHashMap<>();
        for (Map.Entry<Long, AssetEntity> entry : exported.entrySet()) {
            AssetEntity old = entry.getValue();
            if (old == null || !AssetStatus.STALE.name().equals(old.getStatus())) {
                continue;
            }
            AssetEntity replacement = latestApprovedStatic(old.getTenantId(),
                    old.getProductId(), old.getVariant()).orElse(null);
            // nothing to swap in until the new version is approved, and nothing
            // left to do once that version has itself been exported
            if (replacement == null || exported.containsKey(replacement.getId())) {
                continue;
            }
            String sku = skus.computeIfAbsent(old.getProductId(),
                    id -> productCatalog.findById(old.getTenantId(), id)
                            .map(p -> p.sku()).orElse(null));
            replacements.add(new Replacement(old.getProductId(), sku, old.getVariant(),
                    old.getFileName(), old.getId(), replacement.getFileName(),
                    replacement.getId(), replacement.getStatus()));
        }
        return replacements;
    }

    private List<PublicationEntity> exportedPublications(long tenantId) {
        return publicationMapper.selectList(Wrappers.<PublicationEntity>lambdaQuery()
                .eq(PublicationEntity::getTenantId, tenantId)
                .eq(PublicationEntity::getTarget, "AD_EXPORT")
                .eq(PublicationEntity::getStatus, "APPLIED")
                .orderByAsc(PublicationEntity::getId));
    }

    private java.util.Optional<AssetEntity> latestApprovedStatic(long tenantId, long productId,
            String variant) {
        List<AssetEntity> rows = assetMapper.selectList(Wrappers.<AssetEntity>lambdaQuery()
                .eq(AssetEntity::getTenantId, tenantId)
                .eq(AssetEntity::getProductId, productId)
                .eq(AssetEntity::getSpecCode, "AD_STATIC")
                .eq(AssetEntity::getVariant, variant)
                .eq(AssetEntity::getStatus, AssetStatus.APPROVED.name())
                .orderByDesc(AssetEntity::getVersion)
                .last("limit 1"));
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    private List<Long> readIdList(String json) {
        List<Long> ids = new ArrayList<>();
        if (json != null && !json.isBlank()) {
            objectMapper.readTree(json).forEach(node -> ids.add(node.asLong()));
        }
        return ids;
    }
}