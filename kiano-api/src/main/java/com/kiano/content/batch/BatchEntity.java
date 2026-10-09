package com.kiano.content.batch;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * content_batch table (C6): one batch of STANDARD SKUs driven by the
 * orchestrator. The stage of an item is never stored here - it is derived from
 * the actual state of the product's content.
 */
@TableName("content_batch")
public class BatchEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private String name;
    private String status;
    private Boolean nightOnly;
    private @Nullable Long createdBy;
    private OffsetDateTime createdAt;
    private @Nullable OffsetDateTime finishedAt;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getNightOnly() {
        return nightOnly;
    }

    public void setNightOnly(Boolean nightOnly) {
        this.nightOnly = nightOnly;
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

    public @Nullable OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(@Nullable OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}