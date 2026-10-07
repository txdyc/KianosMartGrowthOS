package com.kiano.worker.comfy;

import com.kiano.workerprotocol.WorkflowManifest;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Copies a ComfyUI API-format workflow and fills in the node input fields
 * named by the manifest: uploaded image references for inputs, plain values
 * for params. The original workflow tree is never modified.
 */
@Component
@Profile("worker")
public class WorkflowBinder {

    private final JsonMapper mapper = JsonMapper.builder().build();

    public JsonNode bind(JsonNode workflow, WorkflowManifest manifest,
            Map<String, String> uploadedInputs, Map<String, Object> params) {
        ObjectNode copy = (ObjectNode) workflow.deepCopy();
        if (manifest.inputs() != null) {
            manifest.inputs().forEach((name, binding) -> {
                String uploaded = uploadedInputs.get(name);
                if (uploaded != null) {
                    write(copy, binding, uploaded);
                }
            });
        }
        if (manifest.params() != null) {
            manifest.params().forEach((name, binding) -> {
                Object value = params.get(name);
                if (value != null) {
                    write(copy, binding, value);
                }
            });
        }
        return copy;
    }

    private void write(ObjectNode workflow, WorkflowManifest.Binding binding, Object value) {
        ((ObjectNode) workflow.path(binding.node()).path("inputs"))
                .set(binding.field(), mapper.valueToTree(value));
    }
}
