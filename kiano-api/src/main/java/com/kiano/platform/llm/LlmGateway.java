package com.kiano.platform.llm;

/**
 * Port all LLM calls go through. The only implementation talks to the Claude
 * API via the official anthropic-java SDK; tests inject a fake.
 */
public interface LlmGateway {

    <T> LlmResult<T> complete(LlmRequest<T> request) throws LlmException;
}