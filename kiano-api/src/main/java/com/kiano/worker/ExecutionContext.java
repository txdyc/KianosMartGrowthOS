package com.kiano.worker;

import java.nio.file.Path;
import java.util.Map;

/**
 * Per-job scratch space: the downloaded inputs and the paths the executor
 * must write its outputs to. Removed once the job is reported.
 */
public record ExecutionContext(Path workDir, Map<String, Path> inputs, Map<String, Path> outputs) {
}
