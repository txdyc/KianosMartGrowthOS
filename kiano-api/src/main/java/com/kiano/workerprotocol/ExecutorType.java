package com.kiano.workerprotocol;

/**
 * The executor that runs a generation job: GPU work goes to ComfyUI, the
 * deterministic composition runs in pure Java, H3 API clips are submitted
 * server-side by the api and the final video is composed by FFmpeg in the
 * worker.
 */
public enum ExecutorType {
    COMFYUI,
    COMPOSITE,
    H3,
    FFMPEG
}
