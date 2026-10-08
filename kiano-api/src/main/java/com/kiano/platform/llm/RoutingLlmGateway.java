package com.kiano.platform.llm;

import java.util.EnumMap;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The single {@link LlmGateway} bean. Resolves the effective route for the
 * request's purpose via {@link LlmRouteStore} (configured route or the built-in
 * env default) and dispatches to the matching {@link ProviderClient}. The
 * router keeps no cache: every call re-reads the two tables with one cheap PK
 * query, so setup changes take effect immediately. Vision gating and the
 * env-key check live in {@link #completeWith} (Task 5).
 */
@Component
public class RoutingLlmGateway implements LlmGateway {

    private final LlmRouteStore store;
    private final EnumMap<ProviderKind, ProviderClient> clients;

    public RoutingLlmGateway(LlmRouteStore store, List<ProviderClient> clients) {
        this.store = store;
        this.clients = new EnumMap<>(ProviderKind.class);
        for (ProviderClient client : clients) {
            this.clients.put(client.kind(), client);
        }
    }

    @Override
    public <T> LlmResult<T> complete(LlmRequest<T> request) throws LlmException {
        ResolvedRoute route = store.resolve(request.tenantId(), request.purpose());
        ProviderClient client = clients.get(route.kind());
        if (client == null) {
            throw new LlmException("LLM_CONFIG",
                    "No client for provider kind " + route.kind()
                            + "; check the LLM provider configuration",
                    false);
        }
        return client.complete(request, route);
    }
}