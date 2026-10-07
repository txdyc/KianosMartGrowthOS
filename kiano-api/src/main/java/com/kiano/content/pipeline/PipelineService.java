package com.kiano.content.pipeline;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.ContentTier;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.content.generation.GenerationRunStore;
import com.kiano.content.media.SourceMediaEntity;
import com.kiano.content.media.SourceMediaMapper;
import com.kiano.content.profile.ProductProfileService;
import com.kiano.content.workflow.WorkflowRegistry;
import com.kiano.content.workflow.WorkflowRegistry.ComfyWorkflowView;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Starts the image pipeline: selects the shots that need a cutout, plans the
 * fixed downstream list per cutout (so regenerating one page never cascades
 * into others) and creates one COMFYUI cutout job per shot.
 */
@Service
public class PipelineService {

    private final ProductCatalog productCatalog;
    private final ProductProfileService profileService;
    private final SourceMediaMapper sourceMediaMapper;
    private final WorkflowRegistry workflowRegistry;
    private final GenerationJobStore jobs;
    private final GenerationRunStore runs;
    private final ScenePresetService scenePresets;
    private final PipelineProperties properties;
    private final AuditLog auditLog;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public PipelineService(ProductCatalog productCatalog, ProductProfileService profileService,
            SourceMediaMapper sourceMediaMapper, WorkflowRegistry workflowRegistry,
            GenerationJobStore jobs, GenerationRunStore runs, ScenePresetService scenePresets,
            PipelineProperties properties, AuditLog auditLog, ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate) {
        this.productCatalog = productCatalog;
        this.profileService = profileService;
        this.sourceMediaMapper = sourceMediaMapper;
        this.workflowRegistry = workflowRegistry;
        this.jobs = jobs;
        this.runs = runs;
        this.scenePresets = scenePresets;
        this.properties = properties;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public long start(CurrentUser user, long productId) {
        ProductView product = productCatalog.findById(user.tenantId(), productId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Product not found"));
        if (product.parentId() != null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOT_TOP_LEVEL",
                    "Only top-level products can run the image pipeline");
        }
        if (runs.hasActive(user.tenantId(), productId)) {
            throw new ApiException(HttpStatus.CONFLICT, "PIPELINE_RUNNING",
                    "The image pipeline is already running for this product");
        }
        Map<String, SourceMediaEntity> media = currentMedia(user.tenantId(), productId);
        SourceMediaEntity p1 = media.get("P1");
        if (p1 == null || !"ACCEPTED".equals(p1.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SHOTS_NOT_READY",
                    "P1 must be ACCEPTED before the pipeline can run",
                    Map.of("missing", List.of("P1")));
        }
        ComfyWorkflowView cutoutWorkflow = workflowRegistry.active(user.tenantId(), "CUTOUT")
                .orElseThrow(() -> workflowNotActive("CUTOUT"));
        workflowRegistry.active(user.tenantId(), "SCENE")
                .orElseThrow(() -> workflowNotActive("SCENE"));

        ContentTier tier = profileService.tierOf(user.tenantId(), productId);
        int sceneCount = properties.getSceneCount().getOrDefault(tier.name(), 2);

        // Plan: shot code -> its downstream steps. One cutout per shot; when a
        // shot is both an angle shot and the scene source, the downstream list
        // is merged so the cutout happens once.
        Map<String, List<Map<String, String>>> downstreamByShot = new LinkedHashMap<>();
        downstreamByShot.computeIfAbsent("P1", shot -> new ArrayList<>())
                .add(Map.of("step", "WHITE_MAIN", "variant", "main"));
        for (String shot : properties.getAngleShots()) {
            if (isAccepted(media.get(shot))) {
                downstreamByShot.computeIfAbsent(shot, key -> new ArrayList<>())
                        .add(Map.of("step", "WHITE_ANGLE", "variant", shot));
            }
        }
        if (isAccepted(media.get("P8"))) {
            downstreamByShot.computeIfAbsent("P8", shot -> new ArrayList<>())
                    .add(Map.of("step", "INBOX", "variant", "P8"));
        }
        String sceneSourceShot = null;
        for (String shot : properties.getSceneSourceShots()) {
            if (isAccepted(media.get(shot))) {
                sceneSourceShot = shot;
                List<Map<String, String>> downstream =
                        downstreamByShot.computeIfAbsent(shot, key -> new ArrayList<>());
                for (int i = 1; i <= sceneCount; i++) {
                    downstream.add(Map.of("step", "SCENE_INPUT", "variant", "scene" + i));
                }
                break;
            }
        }

        long runId = runs.create(user.tenantId(), productId, user.userId());
        for (Map.Entry<String, List<Map<String, String>>> entry : downstreamByShot.entrySet()) {
            String shot = entry.getKey();
            SourceMediaEntity source = media.get(shot);
            Map<String, Object> scenePrompts = new LinkedHashMap<>();
            if (shot.equals(sceneSourceShot)) {
                for (ScenePresetService.ScenePrompt prompt : scenePresets.presetsFor(
                        product.categorySlugs(), product.name(), sceneCount)) {
                    scenePrompts.put("scene" + (scenePrompts.size() + 1),
                            Map.of("positive", prompt.positive(), "negative", prompt.negative()));
                }
            }
            createCutoutJob(user.tenantId(), productId, runId, shot, source, entry.getValue(),
                    scenePrompts, cutoutWorkflow);
        }

        auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                String.valueOf(user.userId()), "IMAGE_PIPELINE_STARTED", "product",
                String.valueOf(productId), null,
                Map.of("runId", runId, "cutouts", downstreamByShot.size(), "tier", tier.name()),
                null, "PIPELINE"));
        return runId;
    }

    /** Creates the cutout job, then back-fills the job-id-dependent output key. */
    private void createCutoutJob(long tenantId, long productId, long runId, String shot,
            SourceMediaEntity source, List<Map<String, String>> downstream,
            Map<String, Object> scenePrompts, ComfyWorkflowView workflow) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sourceMediaId", source.getId());
        input.put("shotCode", shot);
        input.put("downstream", downstream);
        input.put("scenePrompts", scenePrompts);
        input.put("workflow", Map.of(
                "code", workflow.code(),
                "version", workflow.version(),
                "json", workflow.workflow(),
                "manifest", workflow.manifest()));
        input.put("models", workflow.manifest().models());
        input.put("params", Map.of());
        input.put("inputs", Map.of("image", source.getObjectKey()));
        input.put("outputs", Map.of());
        long jobId = jobs.create(tenantId, productId, runId, com.kiano.workerprotocol.JobStep.CUTOUT,
                shot, input, List.of());
        backfillOutputs(jobId, Map.of("cutout",
                "t" + tenantId + "/gen/" + jobId + "/cutout.png"));
    }

    /** Replaces input_json.outputs after the job id is known. */
    private void backfillOutputs(long jobId, Map<String, String> outputs) {
        jdbcTemplate.update(
                "update generation_job set input_json = jsonb_set(input_json, '{outputs}', ?::jsonb) "
                        + "where id = ?",
                objectMapper.writeValueAsString(outputs), jobId);
    }

    private Map<String, SourceMediaEntity> currentMedia(long tenantId, long productId) {
        Map<String, SourceMediaEntity> byCode = new LinkedHashMap<>();
        for (SourceMediaEntity row : sourceMediaMapper.selectList(
                Wrappers.<SourceMediaEntity>lambdaQuery()
                        .eq(SourceMediaEntity::getTenantId, tenantId)
                        .eq(SourceMediaEntity::getProductId, productId)
                        .ne(SourceMediaEntity::getStatus, "SUPERSEDED")
                        .orderByAsc(SourceMediaEntity::getId))) {
            byCode.put(row.getShotCode(), row);
        }
        return byCode;
    }

    private static boolean isAccepted(SourceMediaEntity media) {
        return media != null && "ACCEPTED".equals(media.getStatus());
    }

    private static ApiException workflowNotActive(String code) {
        return new ApiException(HttpStatus.CONFLICT, "WORKFLOW_NOT_ACTIVE",
                "No active " + code + " workflow", Map.of("code", code));
    }
}
