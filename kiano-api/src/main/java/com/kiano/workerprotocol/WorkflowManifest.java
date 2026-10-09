package com.kiano.workerprotocol;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Binding manifest uploaded alongside a workflow: which nodes and fields the
 * api fills in for inputs, params and outputs, plus the model inventory used
 * for commercial-license checks. Travels inside input_json to the worker, so
 * it lives with the rest of the shared protocol.
 */
public record WorkflowManifest(String code,
        @Nullable Map<String, Binding> inputs,
        @Nullable Map<String, Binding> params,
        @Nullable Map<String, Binding> outputs,
        @Nullable List<String> requiredNodeClasses,
        @Nullable List<ModelRef> models) {

    /**
     * A named slot bound to a workflow node. For inputs and params {@code field}
     * is the node input the api fills in. For outputs it names the key the file
     * lands under in the ComfyUI history entry ({@code outputs[node][field][0]}):
     * the core SaveVideo node writes {@code images}, VHS_VideoCombine writes
     * {@code gifs}; it defaults to {@code images} when omitted.
     */
    public record Binding(String node, @Nullable String field, @Nullable Integer maxLongSide) {
    }

    /** One model file the workflow depends on. */
    public record ModelRef(String name, String file, String license, String source) {
    }
}
