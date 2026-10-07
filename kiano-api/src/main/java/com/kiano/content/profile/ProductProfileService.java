package com.kiano.content.profile;

import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.ContentTier;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.web.ApiException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * SKU content tiering. Products without a profile row are STANDARD.
 */
@Service
public class ProductProfileService {

    private final ProductProfileMapper profileMapper;
    private final ProductCatalog productCatalog;
    private final AuditLog auditLog;
    private final JdbcTemplate jdbcTemplate;

    public ProductProfileService(ProductProfileMapper profileMapper, ProductCatalog productCatalog,
            AuditLog auditLog, JdbcTemplate jdbcTemplate) {
        this.profileMapper = profileMapper;
        this.productCatalog = productCatalog;
        this.auditLog = auditLog;
        this.jdbcTemplate = jdbcTemplate;
    }

    public ContentTier tierOf(long tenantId, long productId) {
        ProductProfileEntity profile = profileMapper.selectById(productId);
        if (profile == null || profile.getTenantId() == null
                || profile.getTenantId() != tenantId) {
            return ContentTier.STANDARD;
        }
        return ContentTier.valueOf(profile.getContentTier());
    }

    /**
     * Tier of every product of the tenant (STANDARD when no profile exists).
     */
    public Map<Long, ContentTier> tiersFor(long tenantId) {
        Map<Long, ContentTier> tiers = new HashMap<>();
        jdbcTemplate.query(
                "select p.id, pr.content_tier from product p "
                        + "left join product_profile pr on pr.product_id = p.id "
                        + "where p.tenant_id = ?",
                rs -> {
                    String tier = rs.getString(2);
                    tiers.put(rs.getLong(1),
                            tier == null ? ContentTier.STANDARD : ContentTier.valueOf(tier));
                },
                tenantId);
        return tiers;
    }

    public ContentTier setTier(CurrentUser user, long productId, ContentTier tier) {
        ProductView product = productCatalog.findById(user.tenantId(), productId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Product not found"));
        if (product.parentId() != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_TOP_LEVEL_PRODUCT",
                    "Profiles apply to top-level products only");
        }
        ContentTier before = tierOf(user.tenantId(), productId);
        jdbcTemplate.update(
                "insert into product_profile (product_id, tenant_id, role_source, content_tier, updated_at) "
                        + "values (?, ?, 'MANUAL', ?, now()) "
                        + "on conflict (product_id) do update set "
                        + "content_tier = excluded.content_tier, updated_at = excluded.updated_at",
                productId, user.tenantId(), tier.name());
        auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                String.valueOf(user.userId()), "PRODUCT_PROFILE_UPDATED", "product",
                String.valueOf(productId), Map.of("contentTier", before.name()),
                Map.of("contentTier", tier.name()), null, null));
        return tier;
    }
}
