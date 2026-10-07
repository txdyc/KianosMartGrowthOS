package com.kiano.content.template;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Versioned template lookup: the latest APPROVED body per tenant and code,
 * plus the insert the bootstrap uses to ship v1.
 */
@Component
public class TemplateRegistry {

    private final TemplateMapper mapper;

    public TemplateRegistry(TemplateMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<TemplateEntity> latestApproved(long tenantId, String code) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<TemplateEntity>lambdaQuery()
                .eq(TemplateEntity::getTenantId, tenantId)
                .eq(TemplateEntity::getCode, code)
                .eq(TemplateEntity::getStatus, "APPROVED")
                .orderByDesc(TemplateEntity::getVersion)
                .last("limit 1")));
    }

    public @Nullable TemplateEntity find(long tenantId, String code, int version) {
        return mapper.selectOne(Wrappers.<TemplateEntity>lambdaQuery()
                .eq(TemplateEntity::getTenantId, tenantId)
                .eq(TemplateEntity::getCode, code)
                .eq(TemplateEntity::getVersion, version)
                .last("limit 1"));
    }

    public void insertApproved(long tenantId, String code, String kind, int version,
            String body) {
        TemplateEntity entity = new TemplateEntity();
        entity.setTenantId(tenantId);
        entity.setCode(code);
        entity.setKind(kind);
        entity.setVersion(version);
        entity.setBody(body);
        entity.setStatus("APPROVED");
        mapper.insert(entity);
    }
}
