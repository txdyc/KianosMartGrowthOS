package com.kiano.platform.integration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.platform.crypto.CredentialCipher;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Encrypted storage for external integration credentials, keyed by (tenant_id, provider).
 */
@Service
public class IntegrationStore {

    private final IntegrationMapper mapper;
    private final CredentialCipher cipher;
    private final ObjectMapper objectMapper;

    public IntegrationStore(IntegrationMapper mapper, CredentialCipher cipher, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void save(long tenantId, String provider, String accountRef, Object credentials) {
        String json = objectMapper.writeValueAsString(credentials);
        String encrypted = cipher.encrypt(json, aad(tenantId, provider));
        IntegrationEntity existing = mapper.selectOne(Wrappers.<IntegrationEntity>lambdaQuery()
                .eq(IntegrationEntity::getTenantId, tenantId)
                .eq(IntegrationEntity::getProvider, provider));
        if (existing == null) {
            IntegrationEntity entity = new IntegrationEntity();
            entity.setTenantId(tenantId);
            entity.setProvider(provider);
            entity.setAccountRef(accountRef);
            entity.setCredentialsEncrypted(encrypted);
            entity.setStatus("ACTIVE");
            entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            mapper.insert(entity);
        } else {
            existing.setAccountRef(accountRef);
            existing.setCredentialsEncrypted(encrypted);
            existing.setStatus("ACTIVE");
            existing.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            mapper.updateById(existing);
        }
    }

    public Optional<StoredIntegration> find(long tenantId, String provider) {
        IntegrationEntity entity = mapper.selectOne(Wrappers.<IntegrationEntity>lambdaQuery()
                .eq(IntegrationEntity::getTenantId, tenantId)
                .eq(IntegrationEntity::getProvider, provider));
        return Optional.ofNullable(entity).map(this::toView);
    }

    public List<StoredIntegration> findAllActive(String provider) {
        return mapper.selectList(Wrappers.<IntegrationEntity>lambdaQuery()
                        .eq(IntegrationEntity::getProvider, provider)
                        .eq(IntegrationEntity::getStatus, "ACTIVE"))
                .stream().map(this::toView).toList();
    }

    public <T> T credentials(StoredIntegration integration, Class<T> type) {
        IntegrationEntity entity = mapper.selectById(integration.id());
        if (entity == null) {
            throw new IllegalStateException("Integration no longer exists: " + integration.id());
        }
        String json = cipher.decrypt(entity.getCredentialsEncrypted(), aad(integration.tenantId(),
                integration.provider()));
        return objectMapper.readValue(json, type);
    }

    public void markSynced(long tenantId, String provider, Instant at) {
        IntegrationEntity entity = mapper.selectOne(Wrappers.<IntegrationEntity>lambdaQuery()
                .eq(IntegrationEntity::getTenantId, tenantId)
                .eq(IntegrationEntity::getProvider, provider));
        if (entity != null) {
            entity.setLastSyncAt(at.atOffset(ZoneOffset.UTC));
            mapper.updateById(entity);
        }
    }

    private String aad(long tenantId, String provider) {
        return tenantId + ":" + provider;
    }

    private StoredIntegration toView(IntegrationEntity entity) {
        return new StoredIntegration(entity.getId(), entity.getTenantId(), entity.getProvider(),
                entity.getAccountRef(), entity.getStatus(),
                entity.getLastSyncAt() == null ? null : entity.getLastSyncAt().toInstant(),
                entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toInstant());
    }
}
