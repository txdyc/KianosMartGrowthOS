package com.kiano.content.facts;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * product_fact_sheet table. The jsonb columns are mapped through String
 * fields via the stringtype=unspecified JDBC setting, like asset.
 */
@TableName("product_fact_sheet")
public class FactSheetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long productId;
    private Integer version;
    private String factsJson;
    private String fieldSources;
    private String sourceRefs;
    private String status;
    private @Nullable Long llmCallId;
    private @Nullable Long lockedBy;
    private @Nullable OffsetDateTime lockedAt;
    private @Nullable Long createdBy;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getFactsJson() {
        return factsJson;
    }

    public void setFactsJson(String factsJson) {
        this.factsJson = factsJson;
    }

    public String getFieldSources() {
        return fieldSources;
    }

    public void setFieldSources(String fieldSources) {
        this.fieldSources = fieldSources;
    }

    public String getSourceRefs() {
        return sourceRefs;
    }

    public void setSourceRefs(String sourceRefs) {
        this.sourceRefs = sourceRefs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public @Nullable Long getLlmCallId() {
        return llmCallId;
    }

    public void setLlmCallId(@Nullable Long llmCallId) {
        this.llmCallId = llmCallId;
    }

    public @Nullable Long getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(@Nullable Long lockedBy) {
        this.lockedBy = lockedBy;
    }

    public @Nullable OffsetDateTime getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(@Nullable OffsetDateTime lockedAt) {
        this.lockedAt = lockedAt;
    }

    public @Nullable Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(@Nullable Long createdBy) {
        this.createdBy = createdBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}