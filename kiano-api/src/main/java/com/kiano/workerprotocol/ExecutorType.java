package com.kiano.workerprotocol;

/**
 * The executor that runs a generation job: GPU work goes to ComfyUI, the
 * deterministic composition runs in pure Java.
 */
public enum ExecutorType {
    COMFYUI,
    COMPOSITE
}
