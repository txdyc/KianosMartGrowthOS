package com.kiano.platform.llm;

import com.kiano.platform.crypto.CredentialCipher;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read/write access to llm_provider and llm_route, encrypted keys via
 * {@link CredentialCipher} (AAD tenantId:llm:{id}) and the built-in env default
 * route (Anthropic + LLM_PROPERTIES key) when a purpose has no route.
 * Business validation lives in the settings service; this store only moves
 * rows and keeps one query per call so setup changes apply immediately.
 */
@Component
public class LlmRouteStore {

    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    private final LlmProperties properties;

    public LlmRouteStore(JdbcTemplate jdbc, CredentialCipher cipher, LlmProperties properties) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.properties = properties;
    }

    private static final List<LlmPurpose> ROUTED_PURPOSES =
            List.of(LlmPurpose.FACT_DRAFT, LlmPurpose.COPY);

    /** Provider without credentials, as shown in the settings API. */
    public record ProviderView(long id, String name, ProviderKind kind,
            @Nullable String baseUrl, boolean hasKey, String status, Instant updatedAt) {
    }

    /** One route row, complete or marked usingDefault when unset. */
    public record RouteView(LlmPurpose purpose, @Nullable Long providerId,
            @Nullable String model, boolean supportsImages, @Nullable Pricing pricing,
            boolean usingDefault) {
    }

    /**
     * Resolves the effective route for a purpose: configured route (provider
     * joined, key decrypted, updatedAt = later of route/provider) or the env
     * default Anthropic route with providerId null and usingDefault true.
     */
    public ResolvedRoute resolve(long tenantId, LlmPurpose purpose) {
        List<ResolvedRoute> rows = jdbc.query("""
                select r.provider_id, r.model, r.supports_images, r.input_per_mtok,
                       r.output_per_mtok, r.cache_read_per_mtok, r.updated_at as route_updated,
                       p.name as provider_name, p.kind, p.base_url, p.credentials_encrypted,
                       p.updated_at as provider_updated,
                       greatest(r.updated_at, p.updated_at) as effective_updated
                from llm_route r
                join llm_provider p on p.id = r.provider_id
                where r.tenant_id = ? and r.purpose = ?
                """, (rs, i) -> fromRow(tenantId, purpose, rs), tenantId, purpose.name());
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        return envDefault(purpose);
    }

    private ResolvedRoute fromRow(long tenantId, LlmPurpose purpose, ResultSet rs)
            throws SQLException {
        long providerId = rs.getLong("provider_id");
        String apiKey = cipher.decrypt(rs.getString("credentials_encrypted"),
                aad(tenantId, providerId));
        Instant updatedAt = rs.getTimestamp("effective_updated").toInstant();
        return new ResolvedRoute(purpose, providerId,
                ProviderKind.valueOf(rs.getString("kind")),
                rs.getString("provider_name"), rs.getString("base_url"), apiKey,
                rs.getString("model"), rs.getBoolean("supports_images"),
                new Pricing(rs.getBigDecimal("input_per_mtok"),
                        rs.getBigDecimal("output_per_mtok"),
                        rs.getBigDecimal("cache_read_per_mtok")),
                updatedAt, false);
    }

    /** Built-in default: Anthropic, key from kiano.llm.api-key, claude-opus-5-5. */
    private ResolvedRoute envDefault(LlmPurpose purpose) {
        return new ResolvedRoute(purpose, null, ProviderKind.ANTHROPIC, "env-default",
                null, properties.getApiKey(), properties.getModel(), true,
                defaultPricing(), Instant.EPOCH, true);
    }

    private Pricing defaultPricing() {
        LlmProperties.Pricing configured = properties.getPricing().get(properties.getModel());
        if (configured == null) {
            // 4.00 / 20.00 / 0.20 per the spec's built-in default route.
            return new Pricing(BigDecimal.valueOf(4.0), BigDecimal.valueOf(20.0),
                    BigDecimal.valueOf(0.2));
        }
        return new Pricing(configured.getInputPerMtok(), configured.getOutputPerMtok(),
                configured.getCacheReadPerMtok());
    }

    public List<ProviderView> listProviders(long tenantId) {
        return jdbc.query("""
                select id, name, kind, base_url, credentials_encrypted, status, updated_at
                from llm_provider where tenant_id = ? order by id
                """, (rs, i) -> providerView(rs), tenantId);
    }

    public Optional<ProviderView> findProvider(long tenantId, long id) {
        List<ProviderView> rows = jdbc.query("""
                select id, name, kind, base_url, credentials_encrypted, status, updated_at
                from llm_provider where tenant_id = ? and id = ?
                """, (rs, i) -> providerView(rs), tenantId, id);
        return rows.stream().findFirst();
    }

    private ProviderView providerView(ResultSet rs) throws SQLException {
        return new ProviderView(rs.getLong("id"), rs.getString("name"),
                ProviderKind.valueOf(rs.getString("kind")), rs.getString("base_url"),
                rs.getString("credentials_encrypted") != null, rs.getString("status"),
                rs.getTimestamp("updated_at").toInstant());
    }

    public long createProvider(long tenantId, String name, ProviderKind kind,
            @Nullable String baseUrl, String apiKey) {
        // credentials_encrypted is NOT NULL; insert a placeholder, get the id,
        // then write the real ciphertext with the id-based AAD.
        Long id = jdbc.queryForObject("""
                insert into llm_provider (tenant_id, name, kind, base_url, credentials_encrypted,
                                          status, updated_at)
                values (?, ?, ?, ?, '', 'ACTIVE', now()) returning id
                """, Long.class, tenantId, name, kind.name(), baseUrl);
        jdbc.update("""
                update llm_provider set credentials_encrypted = ? where id = ?
                """, cipher.encrypt(apiKey, aad(tenantId, id)), id);
        return id;
    }

    public void updateProvider(long tenantId, long id, String name, @Nullable String baseUrl,
            @Nullable String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            jdbc.update("""
                    update llm_provider set name = ?, base_url = ?, updated_at = now()
                    where tenant_id = ? and id = ?
                    """, name, baseUrl, tenantId, id);
        } else {
            jdbc.update("""
                    update llm_provider set name = ?, base_url = ?,
                           credentials_encrypted = ?, updated_at = now()
                    where tenant_id = ? and id = ?
                    """, name, baseUrl, cipher.encrypt(apiKey, aad(tenantId, id)), tenantId, id);
        }
    }

    public void deleteProvider(long tenantId, long id) {
        jdbc.update("delete from llm_provider where tenant_id = ? and id = ?", tenantId, id);
    }

    public boolean isProviderInUse(long tenantId, long id) {
        Integer count = jdbc.queryForObject(
                "select count(*) from llm_route where tenant_id = ? and provider_id = ?",
                Integer.class, tenantId, id);
        return count != null && count > 0;
    }

    /** Always both purposes; rows without a configured route get usingDefault=true. */
    public List<RouteView> listRoutes(long tenantId) {
        List<RouteView> views = new ArrayList<>();
        for (LlmPurpose purpose : ROUTED_PURPOSES) {
            List<RouteView> rows = jdbc.query("""
                    select r.provider_id, r.model, r.supports_images, r.input_per_mtok,
                           r.output_per_mtok, r.cache_read_per_mtok
                    from llm_route r where r.tenant_id = ? and r.purpose = ?
                    """, (rs, i) -> new RouteView(purpose, rs.getLong("provider_id"),
                            rs.getString("model"), rs.getBoolean("supports_images"),
                            new Pricing(rs.getBigDecimal("input_per_mtok"),
                                    rs.getBigDecimal("output_per_mtok"),
                                    rs.getBigDecimal("cache_read_per_mtok")),
                            false), tenantId, purpose.name());
            if (rows.isEmpty()) {
                views.add(new RouteView(purpose, null, null, false, null, true));
            } else {
                views.add(rows.get(0));
            }
        }
        return views;
    }

    public void saveRoute(long tenantId, LlmPurpose purpose, long providerId, String model,
            boolean supportsImages, Pricing pricing) {
        jdbc.update("""
                insert into llm_route (tenant_id, purpose, provider_id, model, supports_images,
                                       input_per_mtok, output_per_mtok, cache_read_per_mtok,
                                       updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (tenant_id, purpose) do update set
                    provider_id = excluded.provider_id, model = excluded.model,
                    supports_images = excluded.supports_images,
                    input_per_mtok = excluded.input_per_mtok,
                    output_per_mtok = excluded.output_per_mtok,
                    cache_read_per_mtok = excluded.cache_read_per_mtok,
                    updated_at = now()
                """, tenantId, purpose.name(), providerId, model, supportsImages,
                pricing.inputPerMtok(), pricing.outputPerMtok(), pricing.cacheReadPerMtok());
    }

    public void deleteRoute(long tenantId, LlmPurpose purpose) {
        jdbc.update("delete from llm_route where tenant_id = ? and purpose = ?",
                tenantId, purpose.name());
    }

    private static String aad(long tenantId, long providerId) {
        return tenantId + ":llm:" + providerId;
    }
}