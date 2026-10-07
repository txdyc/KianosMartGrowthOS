package com.kiano.content.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.WorkflowManifest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Versioned ComfyUI workflow registry. Registration validates the workflow
 * (ComfyUI API format), every manifest binding and the model licenses; any
 * violation is collected and reported together as 422 WORKFLOW_INVALID.
 * Activation approves one version per code and retires the previous one.
 */
@Component
public class WorkflowRegistry {

    private final ComfyWorkflowMapper mapper;
    private final ObjectMapper objectMapper;
    private final WorkflowProperties properties;
    private final AuditLog auditLog;

    public WorkflowRegistry(ComfyWorkflowMapper mapper, ObjectMapper objectMapper,
            WorkflowProperties properties, AuditLog auditLog) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.auditLog = auditLog;
    }

    public record ComfyWorkflowView(long id, String code, int version, String status, JsonNode workflow,
            WorkflowManifest manifest, Instant createdAt) {
    }

    /** Registers the next version (n+1) of the code as DRAFT. */
    public ComfyWorkflowView register(long tenantId, String workflowJson, String manifestJson,
            @Nullable Long userId) {
        List<String> errors = new ArrayList<>();
        JsonNode workflow = readTree(workflowJson, "workflow.json", errors);
        WorkflowManifest manifest = readManifest(manifestJson, errors);
        if (workflow != null) {
            validateApiFormat(workflow, errors);
        }
        if (manifest != null) {
            validateCode(manifest, errors);
            if (workflow != null) {
                validateBindings(workflow, manifest, errors);
            }
            validateModels(manifest, errors);
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WORKFLOW_INVALID",
                    "Workflow or manifest failed validation", Map.of("errors", errors));
        }
        ComfyWorkflowEntity latest = mapper.selectOne(Wrappers.<ComfyWorkflowEntity>lambdaQuery()
                .eq(ComfyWorkflowEntity::getTenantId, tenantId)
                .eq(ComfyWorkflowEntity::getCode, manifest.code())
                .orderByDesc(ComfyWorkflowEntity::getVersion)
                .last("limit 1"));
        ComfyWorkflowEntity entity = new ComfyWorkflowEntity();
        entity.setTenantId(tenantId);
        entity.setCode(manifest.code());
        entity.setVersion(latest == null ? 1 : latest.getVersion() + 1);
        entity.setWorkflowJson(workflowJson);
        entity.setManifestJson(manifestJson);
        entity.setModelRefs(objectMapper.writeValueAsString(manifest.models()));
        entity.setStatus("DRAFT");
        entity.setCreatedBy(userId);
        mapper.insert(entity);
        return view(entity);
    }

    /** Approves this version, retires the previous approved one, audits (system actor). */
    @Transactional
    public ComfyWorkflowView activate(long tenantId, long id) {
        return activate(tenantId, id, null);
    }

    /** Approves this version, retires the previous approved one, audits. */
    @Transactional
    public ComfyWorkflowView activate(long tenantId, long id, @Nullable Long userId) {
        ComfyWorkflowEntity entity = mapper.selectById(id);
        if (entity == null || entity.getTenantId().longValue() != tenantId) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WORKFLOW_NOT_FOUND",
                    "Workflow " + id + " does not exist");
        }
        String before = entity.getStatus();
        mapper.update(null, Wrappers.<ComfyWorkflowEntity>lambdaUpdate()
                .eq(ComfyWorkflowEntity::getTenantId, tenantId)
                .eq(ComfyWorkflowEntity::getCode, entity.getCode())
                .eq(ComfyWorkflowEntity::getStatus, "APPROVED")
                .ne(ComfyWorkflowEntity::getId, id)
                .set(ComfyWorkflowEntity::getStatus, "RETIRED"));
        entity.setStatus("APPROVED");
        mapper.updateById(entity);
        auditLog.record(new AuditEntry(tenantId,
                userId == null ? ActorType.SYSTEM : ActorType.USER,
                userId == null ? "workflow-bootstrap" : String.valueOf(userId),
                "WORKFLOW_ACTIVATED", "comfy_workflow", String.valueOf(id),
                Map.of("status", before), Map.of("status", "APPROVED"), null, null));
        return view(entity);
    }

    public Optional<ComfyWorkflowView> active(long tenantId, String code) {
        return mapper.selectList(Wrappers.<ComfyWorkflowEntity>lambdaQuery()
                        .eq(ComfyWorkflowEntity::getTenantId, tenantId)
                        .eq(ComfyWorkflowEntity::getCode, code)
                        .eq(ComfyWorkflowEntity::getStatus, "APPROVED"))
                .stream().findFirst().map(this::view);
    }

    public Optional<ComfyWorkflowView> get(long tenantId, long id) {
        ComfyWorkflowEntity entity = mapper.selectById(id);
        if (entity == null || entity.getTenantId().longValue() != tenantId) {
            return Optional.empty();
        }
        return Optional.of(view(entity));
    }

    public List<ComfyWorkflowView> list(long tenantId) {
        return mapper.selectList(Wrappers.<ComfyWorkflowEntity>lambdaQuery()
                        .eq(ComfyWorkflowEntity::getTenantId, tenantId)
                        .orderByAsc(ComfyWorkflowEntity::getCode)
                        .orderByDesc(ComfyWorkflowEntity::getVersion))
                .stream().map(this::view).toList();
    }

    /** True when the tenant has any version of the code. */
    public boolean exists(long tenantId, String code) {
        return mapper.selectCount(Wrappers.<ComfyWorkflowEntity>lambdaQuery()
                .eq(ComfyWorkflowEntity::getTenantId, tenantId)
                .eq(ComfyWorkflowEntity::getCode, code)) > 0;
    }

    private @Nullable JsonNode readTree(String json, String what, List<String> errors) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException ex) {
            errors.add(what + " is not valid JSON: " + ex.getMessage());
            return null;
        }
    }

    private @Nullable WorkflowManifest readManifest(String manifestJson, List<String> errors) {
        try {
            return objectMapper.readValue(manifestJson, WorkflowManifest.class);
        } catch (JacksonException ex) {
            errors.add("manifest.json is invalid: " + ex.getMessage());
            return null;
        }
    }

    /** workflow.json must be ComfyUI API format: node id → {class_type, inputs}. */
    private void validateApiFormat(JsonNode workflow, List<String> errors) {
        if (!workflow.isObject() || workflow.isEmpty()) {
            errors.add("workflow.json must be a non-empty ComfyUI API-format object "
                    + "mapping node ids to nodes");
            return;
        }
        if (workflow.has("nodes") && workflow.path("nodes").isArray()) {
            errors.add("workflow.json is in UI format (top-level nodes array); "
                    + "export the API format instead");
            return;
        }
        for (Map.Entry<String, JsonNode> entry : workflow.properties()) {
            JsonNode node = entry.getValue();
            if (!node.isObject() || !node.has("class_type") || !node.path("inputs").isObject()) {
                errors.add("node '" + entry.getKey() + "' must be an object with class_type and inputs");
            }
        }
    }

    private void validateCode(WorkflowManifest manifest, List<String> errors) {
        if (!"CUTOUT".equals(manifest.code()) && !"SCENE".equals(manifest.code())) {
            errors.add("manifest.code must be CUTOUT or SCENE, got '" + manifest.code() + "'");
        }
    }

    private void validateBindings(JsonNode workflow, WorkflowManifest manifest, List<String> errors) {
        validateSection(manifest.inputs(), "inputs", workflow, errors);
        validateSection(manifest.params(), "params", workflow, errors);
        // Outputs bind to a node only; no field.
        if (manifest.outputs() != null) {
            for (Map.Entry<String, WorkflowManifest.Binding> entry : manifest.outputs().entrySet()) {
                WorkflowManifest.Binding binding = entry.getValue();
                if (binding == null || binding.node() == null
                        || !workflow.path(binding.node()).isObject()) {
                    errors.add("outputs." + entry.getKey() + " references missing node '"
                            + (binding == null ? null : binding.node()) + "'");
                }
            }
        }
    }

    private void validateSection(Map<String, WorkflowManifest.Binding> bindings, String section,
            JsonNode workflow, List<String> errors) {
        if (bindings == null) {
            return;
        }
        for (Map.Entry<String, WorkflowManifest.Binding> entry : bindings.entrySet()) {
            String name = entry.getKey();
            WorkflowManifest.Binding binding = entry.getValue();
            if (binding == null || binding.node() == null) {
                errors.add(section + "." + name + ".node is required");
                continue;
            }
            JsonNode node = workflow.path(binding.node());
            if (!node.isObject() || !node.has("class_type")) {
                errors.add(section + "." + name + " references missing node '" + binding.node() + "'");
                continue;
            }
            if (binding.field() == null) {
                errors.add(section + "." + name + ".field is required");
            } else if (!node.path("inputs").has(binding.field())) {
                errors.add(section + "." + name + " references field '" + binding.field()
                        + "' which does not exist on node '" + binding.node() + "'");
            }
        }
    }

    private void validateModels(WorkflowManifest manifest, List<String> errors) {
        List<WorkflowManifest.ModelRef> models = manifest.models();
        if (models == null || models.isEmpty()) {
            errors.add("manifest.models must list at least one model");
            return;
        }
        for (int i = 0; i < models.size(); i++) {
            WorkflowManifest.ModelRef model = models.get(i);
            String license = model == null ? null : model.license();
            if (license == null || !properties.getAllowedLicenses().contains(license)) {
                errors.add("model '" + (model != null && model.name() != null ? model.name() : i)
                        + "' has license '" + license + "' which is not in the commercial-use whitelist");
            }
        }
    }

    private ComfyWorkflowView view(ComfyWorkflowEntity entity) {
        return new ComfyWorkflowView(entity.getId(), entity.getCode(), entity.getVersion(),
                entity.getStatus(), objectMapper.readTree(entity.getWorkflowJson()),
                objectMapper.readValue(entity.getManifestJson(), WorkflowManifest.class),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().toInstant());
    }
}
