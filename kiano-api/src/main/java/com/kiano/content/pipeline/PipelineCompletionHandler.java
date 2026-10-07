package com.kiano.content.pipeline;

import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.asset.AssetService;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.JobCompletionListener;
import com.kiano.content.workflow.WorkflowRegistry;
import com.kiano.content.workflow.WorkflowRegistry.ComfyWorkflowView;
import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.JobStep;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fans the DAG out as jobs complete: a finished cutout creates its planned
 * page and scene-input jobs, a finished scene input creates the ComfyUI scene
 * job (prompt, fresh seed, parent outputs), and the terminal page steps turn
 * into versioned assets with full provenance.
 */
@Component
public class PipelineCompletionHandler implements JobCompletionListener {

    private static final int CANVAS = 1600;
    private static final double WHITE_OCCUPANCY = 0.825;
    private static final double SCENE_OCCUPANCY = 0.60;
    private static final double SCENE_BOTTOM_MARGIN = 0.12;

    private final GenerationJobStore jobs;
    private final WorkflowRegistry workflowRegistry;
    private final ProductCatalog productCatalog;
    private final AssetService assets;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom random = new SecureRandom();

    public PipelineCompletionHandler(GenerationJobStore jobs, WorkflowRegistry workflowRegistry,
            ProductCatalog productCatalog, AssetService assets, ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate) {
        this.jobs = jobs;
        this.workflowRegistry = workflowRegistry;
        this.productCatalog = productCatalog;
        this.assets = assets;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void onSucceeded(GenerationJob job) {
        switch (job.step()) {
            case CUTOUT -> createDownstream(job);
            case SCENE_INPUT -> createSceneJob(job);
            case WHITE_MAIN, WHITE_ANGLE, INBOX, SCENE -> createAsset(job);
            default -> {
            }
        }
    }

    /** The downstream list was fixed when the pipeline started. */
    private void createDownstream(GenerationJob cutout) {
        for (JsonNode entry : cutout.input().path("downstream")) {
            JobStep step = JobStep.valueOf(entry.path("step").asString());
            String variant = entry.path("variant").asString();
            switch (step) {
                case WHITE_MAIN, WHITE_ANGLE, INBOX -> createPageJob(cutout, step, variant);
                case SCENE_INPUT -> createSceneInputJob(cutout, variant);
                default -> {
                }
            }
        }
    }

    private void createPageJob(GenerationJob cutout, JobStep step, String variant) {
        Map<String, Object> input = baseInput(cutout);
        input.put("composer", Map.of("canvas", CANVAS, "occupancy", WHITE_OCCUPANCY));
        input.put("inputs", Map.of("cutout", cutout.input().path("outputs").path("cutout").asString()));
        long jobId = jobs.create(cutout.tenantId(), cutout.productId(), cutout.runId(), step,
                variant, input, List.of(cutout.id()));
        backfillOutputs(jobId, Map.of("image", objectKey(cutout.tenantId(), jobId, "image")));
    }

    private void createSceneInputJob(GenerationJob cutout, String variant) {
        JsonNode prompt = cutout.input().path("scenePrompts").path(variant);
        Map<String, Object> input = baseInput(cutout);
        input.put("prompt", Map.of(
                "positive", prompt.path("positive").asString(),
                "negative", prompt.path("negative").asString()));
        input.put("placement", Map.of(
                "canvas", CANVAS, "occupancy", SCENE_OCCUPANCY, "bottomMargin", SCENE_BOTTOM_MARGIN));
        input.put("inputs", Map.of("cutout", cutout.input().path("outputs").path("cutout").asString()));
        long jobId = jobs.create(cutout.tenantId(), cutout.productId(), cutout.runId(),
                JobStep.SCENE_INPUT, variant, input, List.of(cutout.id()));
        backfillOutputs(jobId, Map.of(
                "image", objectKey(cutout.tenantId(), jobId, "image"),
                "mask", objectKey(cutout.tenantId(), jobId, "mask")));
    }

    private void createSceneJob(GenerationJob parent) {
        ComfyWorkflowView workflow = workflowRegistry.active(parent.tenantId(), "SCENE")
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "WORKFLOW_NOT_ACTIVE",
                        "No active SCENE workflow", Map.of("code", "SCENE")));
        JsonNode prompt = parent.input().path("prompt");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("positive", prompt.path("positive").asString());
        params.put("negative", prompt.path("negative").asString());
        params.put("seed", random.nextLong() & Long.MAX_VALUE);

        List<Object> workflows = new ArrayList<>(jsonArray(parent.input().path("workflows")));
        workflows.add(Map.of("code", workflow.code(), "version", workflow.version()));
        List<Object> models = new ArrayList<>(jsonArray(parent.input().path("models")));
        if (workflow.manifest().models() != null) {
            models.addAll(workflow.manifest().models());
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sourceMediaId", parent.input().path("sourceMediaId").asLong());
        input.put("shotCode", parent.input().path("shotCode").asString());
        input.put("workflows", workflows);
        input.put("workflow", Map.of(
                "code", workflow.code(),
                "version", workflow.version(),
                "json", workflow.workflow(),
                "manifest", workflow.manifest()));
        input.put("models", models);
        input.put("params", params);
        input.put("jobIds", chain(parent));
        input.put("inputs", Map.of(
                "image", parent.output().path("outputs").path("image").path("objectKey").asString(),
                "mask", parent.output().path("outputs").path("mask").path("objectKey").asString()));
        input.put("outputs", Map.of());
        long jobId = jobs.create(parent.tenantId(), parent.productId(), parent.runId(), JobStep.SCENE,
                parent.variant(), input, List.of(parent.id()));
        backfillOutputs(jobId, Map.of("image", objectKey(parent.tenantId(), jobId, "image")));
    }

    private void createAsset(GenerationJob job) {
        ProductView product = productCatalog.findById(job.tenantId(), job.productId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Product not found"));
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("sourceMediaIds", List.of(job.input().path("sourceMediaId").asLong()));
        provenance.put("workflows", job.input().path("workflows"));
        provenance.put("models", job.input().path("models"));
        if (job.step() == JobStep.SCENE) {
            provenance.put("seed", job.input().path("params").path("seed").asLong());
        } else {
            provenance.put("composer", job.input().path("composer"));
        }
        provenance.put("jobIds", chain(job));
        assets.createFromJob(job, product.sku(), provenance);
    }

    /** Fields every downstream job carries over from its cutout. */
    private static Map<String, Object> baseInput(GenerationJob cutout) {
        JsonNode workflow = cutout.input().path("workflow");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sourceMediaId", cutout.input().path("sourceMediaId").asLong());
        input.put("shotCode", cutout.input().path("shotCode").asString());
        input.put("workflows", List.of(Map.of(
                "code", workflow.path("code").asString(),
                "version", workflow.path("version").asInt())));
        input.put("models", cutout.input().path("models"));
        input.put("jobIds", List.of(cutout.id()));
        input.put("outputs", Map.of());
        return input;
    }

    private static List<Object> chain(GenerationJob job) {
        List<Object> jobIds = new ArrayList<>(jsonArray(job.input().path("jobIds")));
        jobIds.add(job.id());
        return jobIds;
    }

    private static List<Object> jsonArray(JsonNode node) {
        List<Object> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(values::add);
        }
        return values;
    }

    private static String objectKey(long tenantId, long jobId, String name) {
        return "t" + tenantId + "/gen/" + jobId + "/" + name + ".png";
    }

    /** Replaces input_json.outputs after the job id is known. */
    private void backfillOutputs(long jobId, Map<String, String> outputs) {
        jdbcTemplate.update(
                "update generation_job set input_json = jsonb_set(input_json, '{outputs}', ?::jsonb) "
                        + "where id = ?",
                objectMapper.writeValueAsString(outputs), jobId);
    }
}
