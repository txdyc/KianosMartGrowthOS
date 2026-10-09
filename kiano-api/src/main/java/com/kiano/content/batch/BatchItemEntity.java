package com.kiano.content.batch;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * content_batch_item table (C6): one SKU inside a batch. Only the references
 * the orchestration created are stored (image run, staging and production
 * publications) plus the last blocked reason; the stage itself is derived.
 */
@TableName("content_batch_item")
public class BatchItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long batchId;
    private Long productId;
    private Integer position;
    private @Nullable Long imageRunId;
    private @Nullable Long stagingPublicationId;
    private @Nullable Long productionPublicationId;
    private @Nullable String blockedReason;
    private @Nullable String blockedDetails;
    private OffsetDateTime addedAt;
    private @Nullable OffsetDateTime doneAt;

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

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public @Nullable Long getImageRunId() {
        return imageRunId;
    }

    public void setImageRunId(@Nullable Long imageRunId) {
        this.imageRunId = imageRunId;
    }

    public @Nullable Long getStagingPublicationId() {
        return stagingPublicationId;
    }

    public void setStagingPublicationId(@Nullable Long stagingPublicationId) {
        this.stagingPublicationId = stagingPublicationId;
    }

    public @Nullable Long getProductionPublicationId() {
        return productionPublicationId;
    }

    public void setProductionPublicationId(@Nullable Long productionPublicationId) {
        this.productionPublicationId = productionPublicationId;
    }

    public @Nullable String getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(@Nullable String blockedReason) {
        this.blockedReason = blockedReason;
    }

    public @Nullable String getBlockedDetails() {
        return blockedDetails;
    }

    public void setBlockedDetails(@Nullable String blockedDetails) {
        this.blockedDetails = blockedDetails;
    }

    public OffsetDateTime getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(OffsetDateTime addedAt) {
        this.addedAt = addedAt;
    }

    public @Nullable OffsetDateTime getDoneAt() {
        return doneAt;
    }

    public void setDoneAt(@Nullable OffsetDateTime doneAt) {
        this.doneAt = doneAt;
    }
}