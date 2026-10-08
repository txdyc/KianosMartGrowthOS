package com.kiano.platform.llm;

/**
 * One image attached to an LLM call, JPEG-encoded. The label describes what
 * the image shows (e.g. "P5 rating plate") so prompts stay self-documenting.
 */
public record LlmImage(byte[] jpeg, String label) {
}