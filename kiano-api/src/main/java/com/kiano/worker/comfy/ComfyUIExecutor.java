package com.kiano.worker.comfy;

import com.kiano.worker.ExecutionContext;
import com.kiano.worker.ExecutorException;
import com.kiano.worker.GenerationExecutor;
import com.kiano.worker.JobResult;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobPayload;
import com.kiano.workerprotocol.WorkflowManifest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ComfyUI-backed executor for CUTOUT and SCENE: preprocesses and uploads
 * every input, binds params into the workflow embedded in the job, queues
 * the prompt, polls its history until completion, then downloads the bound
 * outputs. A timeout interrupts the prompt and reports a retryable failure.
 */
@Component
@Profile("worker")
public class ComfyUIExecutor implements GenerationExecutor {

    private static final Duration AVAILABILITY_CACHE = Duration.ofSeconds(10);
    private static final long POLL_INTERVAL_MS = 1000;

    private final ComfyClient client;
    private final InputPreprocessor preprocessor;
    private final WorkflowBinder binder;
    private final ComfyProperties properties;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private volatile @Nullable Boolean available;
    private volatile long availableAt;

    public ComfyUIExecutor(ComfyClient client, InputPreprocessor preprocessor,
            WorkflowBinder binder, ComfyProperties properties) {
        this.client = client;
        this.preprocessor = preprocessor;
        this.binder = binder;
        this.properties = properties;
    }

    @Override
    public ExecutorType type() {
        return ExecutorType.COMFYUI;
    }

    @Override
    public boolean available() {
        Boolean cached = available;
        if (cached != null && System.nanoTime() - availableAt < AVAILABILITY_CACHE.toNanos()) {
            return cached;
        }
        boolean up = client.systemStats();
        available = up;
        availableAt = System.nanoTime();
        return up;
    }

    @Override
    public JobResult execute(JobPayload job, ExecutionContext ctx) throws ExecutorException {
        JsonNode workflowSpec = job.input().path("workflow");
        WorkflowManifest manifest =
                mapper.treeToValue(workflowSpec.path("manifest"), WorkflowManifest.class);
        checkNodeClasses(manifest);
        Map<String, Object> params = readParams(job.input().path("params"));
        long startNanos = System.nanoTime();
        Map<String, String> uploaded = uploadInputs(manifest, ctx);
        JsonNode bound = binder.bind(workflowSpec.path("json"), manifest, uploaded, params);
        String promptId = client.queuePrompt(bound, "kiano-" + UUID.randomUUID());
        JsonNode history = await(promptId);
        checkExecutionError(promptId, history);
        downloadOutputs(manifest, history, ctx);
        return new JobResult(gpuSeconds(history, startNanos));
    }

    private void checkNodeClasses(WorkflowManifest manifest) throws ExecutorException {
        List<String> required = manifest.requiredNodeClasses();
        if (required == null || required.isEmpty()) {
            return;
        }
        Set<String> classes = client.objectInfoClasses();
        List<String> missing = required.stream().filter(c -> !classes.contains(c)).toList();
        if (!missing.isEmpty()) {
            throw new ExecutorException("ComfyUI is missing node classes: " + missing, false);
        }
    }

    private Map<String, String> uploadInputs(WorkflowManifest manifest, ExecutionContext ctx)
            throws ExecutorException {
        Map<String, String> uploaded = new LinkedHashMap<>();
        if (manifest.inputs() == null) {
            return uploaded;
        }
        for (Map.Entry<String, WorkflowManifest.Binding> entry : manifest.inputs().entrySet()) {
            try {
                Path prepared = preprocessor.prepare(ctx.inputs().get(entry.getKey()),
                        entry.getValue().maxLongSide());
                uploaded.put(entry.getKey(), client.uploadImage(prepared));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return uploaded;
    }

    private JsonNode await(String promptId) throws ExecutorException {
        long deadline = System.nanoTime() + properties.getTimeout().toNanos();
        while (true) {
            Optional<JsonNode> history = client.history(promptId);
            if (history.isPresent()
                    && history.get().path("status").path("completed").asBoolean(false)) {
                return history.get();
            }
            if (System.nanoTime() >= deadline) {
                client.interrupt();
                throw new ExecutorException("ComfyUI did not finish prompt " + promptId
                        + " within " + properties.getTimeout(), true);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ExecutorException("Interrupted while waiting for prompt " + promptId, true);
            }
        }
    }

    private static void checkExecutionError(String promptId, JsonNode history)
            throws ExecutorException {
        if (!"error".equals(history.path("status").path("status_str").asString(""))) {
            return;
        }
        String message = "unknown error";
        for (JsonNode entry : history.path("status").path("messages")) {
            if ("execution_error".equals(entry.path(0).asString())) {
                message = entry.path(1).path("exception_message").asString(message);
            }
        }
        throw new ExecutorException(
                "ComfyUI execution of prompt " + promptId + " failed: " + message, true);
    }

    private void downloadOutputs(WorkflowManifest manifest, JsonNode history,
            ExecutionContext ctx) throws ExecutorException {
        if (manifest.outputs() == null) {
            return;
        }
        for (Map.Entry<String, WorkflowManifest.Binding> entry : manifest.outputs().entrySet()) {
            JsonNode image = history.path("outputs")
                    .path(entry.getValue().node()).path("images").path(0);
            byte[] bytes = client.view(image.path("filename").asString(),
                    image.path("subfolder").asString(), image.path("type").asString("output"));
            try {
                Files.write(ctx.outputs().get(entry.getKey()), bytes);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
    }

    /**
     * GPU seconds from history timestamps, or wall-clock time as a fallback.
     * History messages are [type, data] tuples with epoch-millisecond
     * timestamps in the data.
     */
    private static double gpuSeconds(JsonNode history, long startNanos) {
        double start = Double.NaN;
        double end = Double.NaN;
        for (JsonNode message : history.path("status").path("messages")) {
            if ("execution_start".equals(message.path(0).asString())) {
                start = message.path(1).path("timestamp").asDouble();
            } else if ("execution_success".equals(message.path(0).asString())) {
                end = message.path(1).path("timestamp").asDouble();
            }
        }
        if (!Double.isNaN(start) && !Double.isNaN(end)) {
            return (end - start) / 1000.0;
        }
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private Map<String, Object> readParams(JsonNode params) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (params.isObject()) {
            params.properties().forEach(entry ->
                    values.put(entry.getKey(), mapper.treeToValue(entry.getValue(), Object.class)));
        }
        return values;
    }
}
