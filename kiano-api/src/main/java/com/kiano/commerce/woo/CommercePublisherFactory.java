package com.kiano.commerce.woo;

import com.kiano.commerce.CommercePublisher;
import com.kiano.commerce.PublishEnvironment;
import com.kiano.platform.integration.IntegrationStore;
import com.kiano.platform.integration.StoredIntegration;
import com.kiano.platform.web.ApiException;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Builds a per-tenant {@link CommercePublisher} for an environment: STAGING
 * uses provider WOOCOMMERCE_STAGING (local Docker), PRODUCTION uses the
 * existing WOOCOMMERCE credentials. An unconfigured environment → 409
 * WOO_ENV_NOT_CONFIGURED.
 */
@Component
public class CommercePublisherFactory {

    /** Integration provider constant, also used as the store platform. */
    public static final String PROVIDER = "WOOCOMMERCE";
    public static final String STAGING_PROVIDER = "WOOCOMMERCE_STAGING";

    private static final Map<PublishEnvironment, String> PROVIDER_BY_ENV = Map.of(
            PublishEnvironment.STAGING, STAGING_PROVIDER,
            PublishEnvironment.PRODUCTION, PROVIDER);

    private final IntegrationStore store;
    private final WooProperties properties;

    public CommercePublisherFactory(IntegrationStore store, WooProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public static String providerFor(PublishEnvironment environment) {
        return PROVIDER_BY_ENV.get(environment);
    }

    public CommercePublisher forEnvironment(long tenantId, PublishEnvironment environment) {
        Optional<StoredIntegration> integration = store.find(tenantId,
                providerFor(environment));
        if (integration.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "WOO_ENV_NOT_CONFIGURED",
                    "WooCommerce credentials for " + environment
                            + " are not configured for this tenant",
                    Map.of("environment", environment.name()));
        }
        WooCredentials credentials = store.credentials(integration.get(), WooCredentials.class);
        return new WooPublisherAdapter(credentials, properties);
    }
}