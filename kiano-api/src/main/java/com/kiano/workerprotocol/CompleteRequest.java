package com.kiano.workerprotocol;

import java.util.List;

/**
 * Job completion: the output files (already uploaded to their presigned URLs)
 * and the GPU time consumed.
 */
public record CompleteRequest(List<OutputFile> outputs, double gpuSeconds) {
}
