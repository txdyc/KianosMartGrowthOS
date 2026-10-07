package com.kiano.commerce.woo;

import com.kiano.commerce.CommercePort;
import com.kiano.platform.integration.IntegrationStore;
import com.kiano.platform.integration.StoredIntegration;
import com.kiano.platform.web.ApiException;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Builds a per-tenant {@link CommercePort} from the stored (encrypted)
 * WooCommerce credentials. Throws WOO_NOT_CONFIGURED when the tenant has
 * no active integration.
 */
@Component
public class CommercePortFactory {

    /** Integration provider constant, also used as the store platform. */
    public static final String PROVIDER = "WOOCOMMERCE";

    private final IntegrationStore store;
    private final WooProperties properties;

    public CommercePortFactory(IntegrationStore store, WooProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public CommercePort forTenant(long tenantId) {
        Optional<StoredIntegration> integration = store.find(tenantId, PROVIDER);
        if (integration.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "WOO_NOT_CONFIGURED",
                    "WooCommerce integration is not configured for this tenant");
        }
        WooCredentials credentials = store.credentials(integration.get(), WooCredentials.class);
        return new WooCommerceAdapter(credentials, properties);
    }
}
