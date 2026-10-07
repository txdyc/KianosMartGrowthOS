package com.kiano.worker.comfy;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.workerprotocol.WorkflowManifest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WorkflowBinderTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final WorkflowBinder binder = new WorkflowBinder();

    @Test
    void bind_writesInputsAndParams_withoutMutatingOriginal() {
        JsonNode workflow = mapper.readTree("""
                {
                  "1": {"class_type": "LoadImage", "inputs": {"image": "placeholder.png"}},
                  "2": {"class_type": "CLIPTextEncode", "inputs": {"text": "placeholder", "strength": 0.5}},
                  "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "cutout"}}
                }
                """);
        WorkflowManifest manifest = new WorkflowManifest("CUTOUT",
                Map.of("image", new WorkflowManifest.Binding("1", "image", 2400)),
                Map.of("positive", new WorkflowManifest.Binding("2", "text", null)),
                Map.of("cutout", new WorkflowManifest.Binding("9", null, null)),
                List.of("LoadImage", "SaveImage"), List.of());

        JsonNode bound = binder.bind(workflow, manifest,
                Map.of("image", "kiano/photo.prepared.png"),
                Map.of("positive", "a fan on a tiled counter", "seed", 42L));

        assertThat(bound.path("1").path("inputs").path("image").asString())
                .isEqualTo("kiano/photo.prepared.png");
        assertThat(bound.path("2").path("inputs").path("text").asString())
                .isEqualTo("a fan on a tiled counter");
        assertThat(bound.path("9").path("class_type").asString()).isEqualTo("SaveImage");

        // The original workflow is untouched — binding works on a deep copy.
        assertThat(workflow.path("1").path("inputs").path("image").asString())
                .isEqualTo("placeholder.png");
        assertThat(workflow.path("2").path("inputs").path("text").asString())
                .isEqualTo("placeholder");
        assertThat(bound).isNotSameAs(workflow);
    }
}
