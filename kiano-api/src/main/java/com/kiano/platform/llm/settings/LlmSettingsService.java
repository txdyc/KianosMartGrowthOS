package com.kiano.platform.llm.settings;

import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.llm.LlmPurpose;
import com.kiano.platform.llm.LlmRouteStore;
import com.kiano.platform.llm.Pricing;
import com.kiano.platform.llm.ProviderKind;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.web.ApiException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OWNER LLM settings: provider + route management with encrypted keys, business
 * validation (https base urls, vision requirement for FACT_DRAFT, provider in
 * use) and audit entries that never contain the key.
 */
@Service
public class LlmSettingsService {

    private final LlmRouteStore store;
    private final AuditLog auditLog;
    private final Environment environment;

    public LlmSettingsService(LlmRouteStore store, AuditLog auditLog, Environment environment) {
        this.store = store;
        this.auditLog = auditLog;
        this.environment = environment;
    }

    public record ProviderInput(String name, ProviderKind kind, @Nullable String baseUrl,
            @Nullable String apiKey) {
    }

    public record RouteInput(long providerId, String model, boolean supportsImages,
            BigDecimal inputPerMtok, BigDecimal outputPerMtok, BigDecimal cacheReadPerMtok) {
    }

    public record LlmSettingsView(List<LlmRouteStore.ProviderView> providers,
            List<LlmRouteStore.RouteView> routes, List<LlmPresets.Preset> presets) {
    }

    public LlmSettingsView get(CurrentUser user) {
        return new LlmSettingsView(store.listProviders(user.tenantId()),
                store.listRoutes(user.tenantId()), LlmPresets.all());
    }

    @Transactional
    public long createProvider(CurrentUser user, ProviderInput input) {
        String name = requiredName(input.name());
        validateBaseUrl(input.kind(), input.baseUrl());
        if (input.apiKey() == null || input.apiKey().isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "Provider Api Key is required when creating a provider");
        }
        List<LlmRouteStore.ProviderView> existing = store.listProviders(user.tenantId());
        if (existing.stream().anyMatch(p -> p.name().equalsIgnoreCase(name))) {
            throw new ApiException(HttpStatus.CONFLICT, "LLM_PROVIDER_NAME_TAKEN",
                    "A provider with this name already exists");
        }
        String baseUrl = normalizeBaseUrl(input.kind(), input.baseUrl());
        long id = store.createProvider(user.tenantId(), name, input.kind(), baseUrl,
                input.apiKey());
        audit(user, "LLM_PROVIDER_CREATED", "provider", String.valueOf(id),
                null, providerState(name, input.kind(), baseUrl, true));
        return id;
    }

    /**
     * Renames a provider, changes its base URL and/or rotates its key. The kind
     * is fixed at creation: switching ANTHROPIC ↔ OPENAI_COMPATIBLE means a
     * different client and key format, so it is a new provider, not an edit.
     */
    @Transactional
    public void updateProvider(CurrentUser user, long id, ProviderInput input) {
        LlmRouteStore.ProviderView existing = requireProvider(user.tenantId(), id);
        if (input.kind() != null && input.kind() != existing.kind()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LLM_PROVIDER_KIND_IMMUTABLE",
                    "A provider's kind cannot change; add a new provider instead");
        }
        String name = requiredName(input.name());
        ProviderKind kind = existing.kind();
        validateBaseUrl(kind, input.baseUrl());
        String baseUrl = normalizeBaseUrl(kind, input.baseUrl());
        store.updateProvider(user.tenantId(), id, name, baseUrl, input.apiKey());
        // ANTHROPIC providers have a null base URL, so these maps must allow nulls.
        audit(user, "LLM_PROVIDER_UPDATED", "provider", String.valueOf(id),
                providerState(existing.name(), existing.kind(), existing.baseUrl(),
                        existing.hasKey()),
                providerState(name, kind, baseUrl, storedKey(user.tenantId(), id)));
    }

    @Transactional
    public void deleteProvider(CurrentUser user, long id) {
        requireProvider(user.tenantId(), id);
        if (store.isProviderInUse(user.tenantId(), id)) {
            throw new ApiException(HttpStatus.CONFLICT, "PROVIDER_IN_USE",
                    "This provider is still used by a route; point the route elsewhere first");
        }
        store.deleteProvider(user.tenantId(), id);
        audit(user, "LLM_PROVIDER_DELETED", "provider", String.valueOf(id),
                Map.of("id", id), null);
    }

    @Transactional
    public void saveRoute(CurrentUser user, LlmPurpose purpose, RouteInput input) {
        if (purpose != LlmPurpose.FACT_DRAFT && purpose != LlmPurpose.COPY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Route purposes are limited to FACT_DRAFT and COPY");
        }
        requireProvider(user.tenantId(), input.providerId());
        if (input.model() == null || input.model().isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "Route model must not be empty");
        }
        if (negative(input.inputPerMtok()) || negative(input.outputPerMtok())
                || negative(input.cacheReadPerMtok())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Prices must not be negative");
        }
        if (purpose == LlmPurpose.FACT_DRAFT && !input.supportsImages()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_REQUIRES_VISION",
                    "The fact-draft model must be able to read images");
        }
        Pricing pricing = new Pricing(input.inputPerMtok(), input.outputPerMtok(),
                input.cacheReadPerMtok());
        store.saveRoute(user.tenantId(), purpose, input.providerId(), input.model(),
                input.supportsImages(), pricing);
        audit(user, "LLM_ROUTE_SAVED", "route", purpose.name(),
                routeBefore(user.tenantId(), purpose),
                Map.of("purpose", purpose.name(), "providerId", input.providerId(),
                        "model", input.model(), "supportsImages", input.supportsImages(),
                        "inputPerMtok", input.inputPerMtok(), "outputPerMtok",
                        input.outputPerMtok(), "cacheReadPerMtok", input.cacheReadPerMtok()));
    }

    @Transactional
    public void deleteRoute(CurrentUser user, LlmPurpose purpose) {
        if (purpose != LlmPurpose.FACT_DRAFT && purpose != LlmPurpose.COPY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Route purposes are limited to FACT_DRAFT and COPY");
        }
        store.deleteRoute(user.tenantId(), purpose);
        audit(user, "LLM_ROUTE_DELETED", "route", purpose.name(),
                Map.of("purpose", purpose.name()), null);
    }

    // ---- helpers ----

    private String requiredName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                    "Provider name must not be empty");
        }
        return name.trim();
    }

    private void validateBaseUrl(ProviderKind kind, @Nullable String baseUrl) {
        if (kind == ProviderKind.OPENAI_COMPATIBLE
                && (baseUrl == null || baseUrl.isBlank())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LLM_BASE_URL_INVALID",
                    "An OpenAI-compatible provider requires a base URL");
        }
        String normalized = normalizeBaseUrl(kind, baseUrl);
        if (normalized != null) {
            validateBaseUrlScheme(normalized);
        }
    }

    /**
     * ANTHROPIC never carries a base url; OPENAI_COMPATIBLE keeps it (already
     * validated in {@link #validateBaseUrl}). Foreign key of the base url rule:
     * https only, with localhost/host.docker.internal allowed on local/test.
     */
    private @Nullable String normalizeBaseUrl(ProviderKind kind, @Nullable String baseUrl) {
        if (kind == ProviderKind.ANTHROPIC || baseUrl == null) {
            return null;
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void validateBaseUrlScheme(String baseUrl) {
        if (baseUrl.startsWith("https://")) {
            return;
        }
        boolean localAllowed = List.of(environment.getActiveProfiles())
                .contains("local") || List.of(environment.getActiveProfiles()).contains("test");
        boolean localUrl = baseUrl.startsWith("http://localhost")
                || baseUrl.startsWith("http://127.0.0.1")
                || baseUrl.startsWith("http://host.docker.internal");
        if (localAllowed && localUrl) {
            return;
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LLM_BASE_URL_INVALID",
                "An OpenAI-compatible base URL must start with https://");
    }

    private LlmRouteStore.ProviderView requireProvider(long tenantId, long id) {
        return store.findProvider(tenantId, id).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "NOT_FOUND", "Provider " + id + " does not exist"));
    }

    private boolean storedKey(long tenantId, long id) {
        return store.findProvider(tenantId, id).map(LlmRouteStore.ProviderView::hasKey)
                .orElse(false);
    }

    private static boolean negative(@Nullable BigDecimal value) {
        return value == null || value.signum() < 0;
    }

    private @Nullable Map<String, Object> routeBefore(long tenantId, LlmPurpose purpose) {
        LlmRouteStore.RouteView before = store.listRoutes(tenantId).stream()
                .filter(r -> r.purpose() == purpose).findFirst().orElse(null);
        if (before == null || before.usingDefault()) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("providerId", before.providerId());
        map.put("model", before.model());
        map.put("supportsImages", before.supportsImages());
        if (before.pricing() != null) {
            map.put("inputPerMtok", before.pricing().inputPerMtok());
            map.put("outputPerMtok", before.pricing().outputPerMtok());
            map.put("cacheReadPerMtok", before.pricing().cacheReadPerMtok());
        }
        return map;
    }

    /** Audit snapshot of a provider (never the key); LinkedHashMap allows a null base URL. */
    private Map<String, Object> providerState(String name, ProviderKind kind,
            @Nullable String baseUrl, boolean hasKey) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("kind", kind.name());
        map.put("baseUrl", baseUrl);
        map.put("hasKey", hasKey);
        return map;
    }

    private void audit(CurrentUser user, String action, String targetType, String targetId,
            @Nullable Object before, @Nullable Object after) {
        auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                String.valueOf(user.userId()), action, targetType, targetId, before, after,
                null, "LLM"));
    }
}