package com.kiano.workerprotocol;

import org.jspecify.annotations.Nullable;

/**
 * Pipeline steps. {@code assetSpecCode} is null for intermediate steps that
 * produce no reviewable asset.
 */
public enum JobStep {

    CUTOUT(ExecutorType.COMFYUI, null),
    SCENE_INPUT(ExecutorType.COMPOSITE, null),
    WHITE_MAIN(ExecutorType.COMPOSITE, "PAGE_MAIN"),
    WHITE_ANGLE(ExecutorType.COMPOSITE, "PAGE_ANGLE"),
    INBOX(ExecutorType.COMPOSITE, "PAGE_INBOX"),
    SCENE(ExecutorType.COMFYUI, "PAGE_SCENE");

    private final ExecutorType executor;
    private final @Nullable String assetSpecCode;

    JobStep(ExecutorType executor, @Nullable String assetSpecCode) {
        this.executor = executor;
        this.assetSpecCode = assetSpecCode;
    }

    public ExecutorType executor() {
        return executor;
    }

    public @Nullable String assetSpecCode() {
        return assetSpecCode;
    }
}
