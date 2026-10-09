package com.kiano.workerprotocol;

import org.jspecify.annotations.Nullable;

/**
 * Pipeline steps. {@code assetSpecCode} is null for intermediate steps that
 * produce no reviewable asset. H3_CLIP_API is executed by the api itself
 * (never leased to workers); H3_CLIP_LOCAL runs on the worker's ComfyUI.
 */
public enum JobStep {

    CUTOUT(ExecutorType.COMFYUI, null),
    SCENE_INPUT(ExecutorType.COMPOSITE, null),
    WHITE_MAIN(ExecutorType.COMPOSITE, "PAGE_MAIN"),
    WHITE_ANGLE(ExecutorType.COMPOSITE, "PAGE_ANGLE"),
    INBOX(ExecutorType.COMPOSITE, "PAGE_INBOX"),
    SCENE(ExecutorType.COMFYUI, "PAGE_SCENE"),
    H3_CLIP_API(ExecutorType.H3, null),
    H3_CLIP_LOCAL(ExecutorType.COMFYUI, null),
    VIDEO_COMPOSE(ExecutorType.FFMPEG, "VIDEO_AD");

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
