package com.kiano.content.asset;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.jspecify.annotations.Nullable;

/**
 * asset table. The jsonb columns are mapped through String fields via the
 * stringtype=unspecified JDBC setting.
 */
@TableName("asset")
public class AssetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long productId;
    private String specCode;
    private String variant;
    private Integer version;
    private String kind;
    private @Nullable String objectKey;
    private @Nullable String thumbObjectKey;
    private @Nullable String textBody;
    private @Nullable Integer width;
    private @Nullable Integer height;
    private @Nullable BigDecimal durationS;
    private String status;
    private String precheckJson;
    private String provenanceJson;
    private @Nullable BigDecimal aiRatio;
    private @Nullable Boolean dependsOnPrice;
    private @Nullable BigDecimal priceSnapshot;
    private @Nullable Integer factVersion;
    private @Nullable String fileName;
    private @Nullable Long runId;
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

    public String getSpecCode() {
        return specCode;
    }

    public void setSpecCode(String specCode) {
        this.specCode = specCode;
    }

    public String getVariant() {
        return variant;
    }

    public void setVariant(String variant) {
        this.variant = variant;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public @Nullable String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(@Nullable String objectKey) {
        this.objectKey = objectKey;
    }

    public @Nullable String getThumbObjectKey() {
        return thumbObjectKey;
    }

    public void setThumbObjectKey(@Nullable String thumbObjectKey) {
        this.thumbObjectKey = thumbObjectKey;
    }

    public @Nullable String getTextBody() {
        return textBody;
    }

    public void setTextBody(@Nullable String textBody) {
        this.textBody = textBody;
    }

    public @Nullable Integer getWidth() {
        return width;
    }

    public void setWidth(@Nullable Integer width) {
        this.width = width;
    }

    public @Nullable Integer getHeight() {
        return height;
    }

    public void setHeight(@Nullable Integer height) {
        this.height = height;
    }

    public @Nullable BigDecimal getDurationS() {
        return durationS;
    }

    public void setDurationS(@Nullable BigDecimal durationS) {
        this.durationS = durationS;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPrecheckJson() {
        return precheckJson;
    }

    public void setPrecheckJson(String precheckJson) {
        this.precheckJson = precheckJson;
    }

    public String getProvenanceJson() {
        return provenanceJson;
    }

    public void setProvenanceJson(String provenanceJson) {
        this.provenanceJson = provenanceJson;
    }

    public @Nullable BigDecimal getAiRatio() {
        return aiRatio;
    }

    public void setAiRatio(@Nullable BigDecimal aiRatio) {
        this.aiRatio = aiRatio;
    }

    public @Nullable Boolean getDependsOnPrice() {
        return dependsOnPrice;
    }

    public void setDependsOnPrice(@Nullable Boolean dependsOnPrice) {
        this.dependsOnPrice = dependsOnPrice;
    }

    public @Nullable BigDecimal getPriceSnapshot() {
        return priceSnapshot;
    }

    public void setPriceSnapshot(@Nullable BigDecimal priceSnapshot) {
        this.priceSnapshot = priceSnapshot;
    }

    public @Nullable Integer getFactVersion() {
        return factVersion;
    }

    public void setFactVersion(@Nullable Integer factVersion) {
        this.factVersion = factVersion;
    }

    public @Nullable String getFileName() {
        return fileName;
    }

    public void setFileName(@Nullable String fileName) {
        this.fileName = fileName;
    }

    public @Nullable Long getRunId() {
        return runId;
    }

    public void setRunId(@Nullable Long runId) {
        this.runId = runId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
