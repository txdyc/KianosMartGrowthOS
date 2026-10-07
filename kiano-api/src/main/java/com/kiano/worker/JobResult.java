package com.kiano.worker;

/**
 * Outcome of a successful execution: the GPU seconds consumed (0 for CPU
 * work); output bytes are read from the paths written by the executor.
 */
public record JobResult(double gpuSeconds) {
}
