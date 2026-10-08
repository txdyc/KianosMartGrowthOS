package com.kiano.content.publish;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * publication table. jsonb columns are mapped through String fields via the
 * stringtype=unspecified JDBC setting.
 */
@TableName("publication")
public class PublicationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long productId;
    private String environment;
    private String target;
    private String assetIds;
    private @Nullable String externalRef;
    private @Nullable String beforeJson;
    private @Nullable String afterJson;
    private String uploadedMediaIds;
    private String archivedAssetIds;
    private String status;
    private Boolean needsAttention;
    private @Nullable String error;
    private @Nullable Long publishedBy;
    private @Nullable OffsetDateTime publishedAt;
    private OffsetDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getAssetIds() {
        return assetIds;
    }

    public void setAssetIds(String assetIds) {
        this.assetIds = assetIds;
    }

    public @Nullable String getExternalRef() {
        return externalRef;
    }

    public void setExternalRef(@Nullable String externalRef) {
        this.externalRef = externalRef;
    }

    public @Nullable String getBeforeJson() {
        return beforeJson;
    }

    public void setBeforeJson(@Nullable String beforeJson) {
        this.beforeJson = beforeJson;
    }

    public @Nullable String getAfterJson() {
        return afterJson;
    }

    public void setAfterJson(@Nullable String afterJson) {
        this.afterJson = afterJson;
    }

    public String getUploadedMediaIds() {
        return uploadedMediaIds;
    }

    public void setUploadedMediaIds(String uploadedMediaIds) {
        this.uploadedMediaIds = uploadedMediaIds;
    }

    public String getArchivedAssetIds() {
        return archivedAssetIds;
    }

    public void setArchivedAssetIds(String archivedAssetIds) {
        this.archivedAssetIds = archivedAssetIds;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getNeedsAttention() {
        return needsAttention;
    }

    public void setNeedsAttention(Boolean needsAttention) {
        this.needsAttention = needsAttention;
    }

    public @Nullable String getError() {
        return error;
    }

    public void setError(@Nullable String error) {
        this.error = error;
    }

    public @Nullable Long getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(@Nullable Long publishedBy) {
        this.publishedBy = publishedBy;
    }

    public @Nullable OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(@Nullable OffsetDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}