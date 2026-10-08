package com.kiano.platform.llm;

/**
 * The two provider families. ANTHROPIC uses the official SDK; OPENAI_COMPATIBLE
 * speaks the OpenAI chat/completions format (e.g. DeepSeek or any compatible
 * gateway), with local schema validation of the reply.
 */
public enum ProviderKind {
    ANTHROPIC,
    OPENAI_COMPATIBLE
}