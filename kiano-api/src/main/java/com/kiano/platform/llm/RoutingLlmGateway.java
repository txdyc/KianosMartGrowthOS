package com.kiano.platform.llm;

import java.util.EnumMap;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The single {@link LlmGateway} bean. Resolves the effective route for the
 * request's purpose via {@link LlmRouteStore} (configured route or the built-in
 * env default) and dispatches to the matching {@link ProviderClient}. The
 * router keeps no cache: every call re-reads the two tables with one cheap PK
 * query, so setup changes take effect immediately. {@link #completeWith} also
 * guards against image requests being sent to models that cannot see images
 * (LLM_MODEL_NO_VISION, e.g. after a direct DB edit) and against a missing
 * env key on the built-in default route.
 */
@Component
public class RoutingLlmGateway implements LlmGateway {

    private final LlmRouteStore store;
    private final LlmCallRecorder recorder;
    private final EnumMap<ProviderKind, ProviderClient> clients;

    public RoutingLlmGateway(LlmRouteStore store, LlmCallRecorder recorder,
            List<ProviderClient> clients) {
        this.store = store;
        this.recorder = recorder;
        this.clients = new EnumMap<>(ProviderKind.class);
        for (ProviderClient client : clients) {
            this.clients.put(client.kind(), client);
        }
    }

    @Override
    public <T> LlmResult<T> complete(LlmRequest<T> request) throws LlmException {
        ResolvedRoute route = store.resolve(request.tenantId(), request.purpose());
        return completeWith(request, route);
    }

    /**
     * Executes a call against an explicitly resolved route. Public so the
     * settings connection tester can reuse the same guards and dispatch.
     */
    public <T> LlmResult<T> completeWith(LlmRequest<T> request, ResolvedRoute route)
            throws LlmException {
        long started = System.nanoTime();
        if (!route.supportsImages() && !request.images().isEmpty()) {
            recorder.record(new LlmCallRecorder.LlmCallRow(request.tenantId(), request.purpose(),
                    route.model(), "ERROR", 0, 0, 0, 0, null, latencyMs(started), null,
                    "LLM_MODEL_NO_VISION: route does not support images",
                    route.providerLabel()));
            throw new LlmException("LLM_MODEL_NO_VISION",
                    "The selected model cannot see images; pick a vision-capable model "
                            + "or remove the images",
                    false);
        }
        if (route.usingDefault() && isBlank(route.apiKey())) {
            recorder.record(new LlmCallRecorder.LlmCallRow(request.tenantId(), request.purpose(),
                    route.model(), "ERROR", 0, 0, 0, 0, null, latencyMs(started), null,
                    "LLM_NOT_CONFIGURED: ANTHROPIC_API_KEY is not set", route.providerLabel()));
            throw new LlmException("LLM_NOT_CONFIGURED",
                    "ANTHROPIC_API_KEY is not set; configure it in .env to enable Claude calls",
                    false);
        }
        ProviderClient client = clients.get(route.kind());
        if (client == null) {
            throw new LlmException("LLM_CONFIG",
                    "No client for provider kind " + route.kind()
                            + "; check the LLM provider configuration",
                    false);
        }
        return client.complete(request, route);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static int latencyMs(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000L);
    }
}