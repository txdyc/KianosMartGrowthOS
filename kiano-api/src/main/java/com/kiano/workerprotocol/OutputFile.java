package com.kiano.workerprotocol;

import org.jspecify.annotations.Nullable;

/**
 * One output produced by a job, with the measurements the worker computed.
 */
public record OutputFile(String name, String objectKey, @Nullable Integer width, @Nullable Integer height,
        @Nullable Double durationS, @Nullable String sha256) {
}
