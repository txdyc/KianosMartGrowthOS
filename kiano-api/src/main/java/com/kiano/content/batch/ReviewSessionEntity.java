package com.kiano.content.batch;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * review_session table (C6 Task 5): one workbench visit of one SKU. The
 * service clamps every reported active time, so an idle or suspended tab can
 * never inflate the review-time acceptance metric.
 */
@TableName("review_session")
public class ReviewSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long userId;
    private Long productId;
    private @Nullable Long batchId;
    private String kind;
    private Double activeSeconds;
    private OffsetDateTime startedAt;
    private OffsetDateTime lastHeartbeatAt;
    private @Nullable OffsetDateTime finishedAt;
    private @Nullable String outcome;

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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public @Nullable Long getBatchId() {
        return batchId;
    }

    public void setBatchId(@Nullable Long batchId) {
        this.batchId = batchId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Double getActiveSeconds() {
        return activeSeconds;
    }

    public void setActiveSeconds(Double activeSeconds) {
        this.activeSeconds = activeSeconds;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(OffsetDateTime lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public @Nullable OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(@Nullable OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public @Nullable String getOutcome() {
        return outcome;
    }

    public void setOutcome(@Nullable String outcome) {
        this.outcome = outcome;
    }
}