package com.kiano.commerce.web;

import com.kiano.commerce.CommerceException;
import com.kiano.commerce.PublishEnvironment;
import com.kiano.commerce.woo.CommercePublisherFactory;
import com.kiano.commerce.woo.WooCredentials;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.integration.IntegrationStore;
import com.kiano.platform.integration.StoredIntegration;
import com.kiano.platform.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OWNER-only WooCommerce integration settings. Credentials are stored
 * encrypted; responses and audit entries never contain the application
 * password. A blank application password on PUT keeps the stored one.
 */
@RestController
@RequestMapping("/api/v1/integrations/woocommerce")
public class WooIntegrationController {

    private final IntegrationStore integrationStore;
    private final CommercePublisherFactory commercePublisherFactory;
    private final AuditLog auditLog;
    private final JdbcTemplate jdbcTemplate;

    public WooIntegrationController(IntegrationStore integrationStore,
            CommercePublisherFactory commercePublisherFactory,
            AuditLog auditLog, JdbcTemplate jdbcTemplate) {
        this.integrationStore = integrationStore;
        this.commercePublisherFactory = commercePublisherFactory;
        this.auditLog = auditLog;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record SaveWooRequest(@NotBlank String baseUrl, @NotBlank String username,
            String applicationPassword) {
    }

    public record WooStatus(String baseUrl, String username, boolean configured, Instant lastSyncAt) {
    }

    @PutMapping
    @PreAuthorize("hasRole('OWNER')")
    public WooStatus save(CurrentUser user, @RequestParam(defaultValue = "PRODUCTION")
            PublishEnvironment environment,
            @Valid @RequestBody SaveWooRequest request) {
        String provider = CommercePublisherFactory.providerFor(environment);
        String baseUrl = stripTrailingSlash(request.baseUrl().trim());
        String username = request.username().trim();
        Optional<StoredIntegration> existing = integrationStore.find(user.tenantId(), provider);
        String password = request.applicationPassword();
        if ((password == null || password.isBlank()) && existing.isPresent()) {
            password = integrationStore.credentials(existing.get(), WooCredentials.class)
                    .applicationPassword();
        }
        String beforeBaseUrl = findStoreBaseUrl(user.tenantId(), provider);
        integrationStore.save(user.tenantId(), provider, username,
                new WooCredentials(baseUrl, username, password));
        jdbcTemplate.update(
                "insert into store (tenant_id, platform, base_url) values (?, ?, ?) "
                        + "on conflict (tenant_id, platform) do update set base_url = excluded.base_url",
                user.tenantId(), provider, baseUrl);
        auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                String.valueOf(user.userId()), "INTEGRATION_UPDATED", "integration",
                provider, before(user.tenantId(), existing, beforeBaseUrl),
                after(baseUrl, username), environment.name(), "WOO"));
        return new WooStatus(baseUrl, username, true,
                existing.map(StoredIntegration::lastSyncAt).orElse(null));
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER')")
    public Object status(CurrentUser user, @RequestParam(defaultValue = "PRODUCTION")
            PublishEnvironment environment) {
        String provider = CommercePublisherFactory.providerFor(environment);
        Optional<StoredIntegration> existing = integrationStore.find(user.tenantId(), provider);
        if (existing.isEmpty()) {
            return Map.of("configured", false, "environment", environment.name());
        }
        return new WooStatus(findStoreBaseUrl(user.tenantId(), provider),
                existing.get().accountRef(), true, existing.get().lastSyncAt());
    }

    @PostMapping("/test")
    @PreAuthorize("hasRole('OWNER')")
    public Map<String, Object> test(CurrentUser user, @RequestParam(defaultValue = "PRODUCTION")
            PublishEnvironment environment) {
        try {
            commercePublisherFactory.forEnvironment(user.tenantId(), environment).findBySku("");
            return Map.of("ok", true);
        } catch (ApiException | CommerceException ex) {
            return Map.of("ok", false, "code",
                    ex instanceof ApiException api ? api.getCode()
                            : ((CommerceException) ex).code(),
                    "message", ex.getMessage() == null ? "" : ex.getMessage());
        }
    }

    private Map<String, Object> before(long tenantId, Optional<StoredIntegration> existing,
            String beforeBaseUrl) {
        if (existing.isEmpty() && beforeBaseUrl == null) {
            return null;
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("baseUrl", beforeBaseUrl);
        before.put("username", existing.map(StoredIntegration::accountRef).orElse(null));
        return before;
    }

    private static Map<String, Object> after(String baseUrl, String username) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("baseUrl", baseUrl);
        after.put("username", username);
        return after;
    }

    private String findStoreBaseUrl(long tenantId, String provider) {
        List<String> urls = jdbcTemplate.queryForList(
                "select base_url from store where tenant_id = ? and platform = ?",
                String.class, tenantId, provider);
        return urls.isEmpty() ? null : urls.get(0);
    }

    private static String stripTrailingSlash(String baseUrl) {
        String trimmed = baseUrl;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
