package com.kiano.content.video.h3;

/**
 * The two H3 backends a clip can use: the local ComfyUI workflow
 * ({@code H3_I2V}) or the provider API. Which one is chosen - and whether a
 * failed clip may escalate from local to API - is decided by
 * {@link H3BackendSelector}.
 */
public enum H3Backend {
    LOCAL, API
}