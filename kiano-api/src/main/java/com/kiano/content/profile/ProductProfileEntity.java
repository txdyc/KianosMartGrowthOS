package com.kiano.content.profile;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

/**
 * product_profile table. The primary key is the product id (no auto
 * generation); content_tier defaults to STANDARD when no row exists.
 */
@TableName("product_profile")
public class ProductProfileEntity {

    @TableId(type = IdType.INPUT)
    private Long productId;

    private Long tenantId;

    private String skuRole;

    private String roleSource;

    private String contentTier;

    private OffsetDateTime updatedAt;

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getSkuRole() {
        return skuRole;
    }

    public void setSkuRole(String skuRole) {
        this.skuRole = skuRole;
    }

    public String getRoleSource() {
        return roleSource;
    }

    public void setRoleSource(String roleSource) {
        this.roleSource = roleSource;
    }

    public String getContentTier() {
        return contentTier;
    }

    public void setContentTier(String contentTier) {
        this.contentTier = contentTier;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
