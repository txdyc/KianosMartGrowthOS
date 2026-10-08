package com.kiano.platform.llm;

/**
 * A concrete upstream-API adapter: takes a resolved route and executes a call
 * against that provider. Implementations do NOT implement {@link LlmGateway} -
 * the router does - so tests can still inject a {@code @Primary} fake gateway.
 */
public interface ProviderClient {

    /** The provider kind this client speaks to. */
    ProviderKind kind();

    /**
     * Sends the request to the provider described by {@code route}. Model, key,
     * pricing (and base URL) always come from the route, never from global
     * properties, so per-task routing takes effect immediately.
     */
    <T> LlmResult<T> complete(LlmRequest<T> request, ResolvedRoute route) throws LlmException;
}