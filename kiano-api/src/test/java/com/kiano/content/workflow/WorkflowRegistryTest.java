package com.kiano.content.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.platform.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Workflow registry: versioned registration with binding and license
 * validation, activation retiring the previous approved version, and the
 * classpath bootstrap for baseline workflows.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class WorkflowRegistryTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkflowRegistry registry;

    @Autowired
    private WorkflowBootstrap bootstrap;

    private long tenantId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from comfy_workflow");
        jdbcTemplate.update("delete from audit_log where target_type = 'comfy_workflow'");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'", Long.class);
    }

    @Test
    void register_good_isDraftVersion1_thenActivateApproves() {
        WorkflowRegistry.ComfyWorkflowView view = registry.register(tenantId,
                fixture("good/workflow.json"), fixture("good/manifest.json"), null);

        assertThat(view.code()).isEqualTo("SCENE");
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.status()).isEqualTo("DRAFT");
        assertThat(view.manifest().inputs().get("image").node()).isEqualTo("10");
        assertThat(view.workflow().has("30")).isTrue();

        WorkflowRegistry.ComfyWorkflowView activated = registry.activate(tenantId, view.id());

        assertThat(activated.status()).isEqualTo("APPROVED");
        assertThat(registry.active(tenantId, "SCENE")).contains(activated);
        assertThat(registry.get(tenantId, view.id())).contains(activated);

        Integer audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where action = 'WORKFLOW_ACTIVATED' and target_id = ?",
                Integer.class, String.valueOf(view.id()));
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void register_secondVersion_increments_andActivateRetiresPrevious() {
        WorkflowRegistry.ComfyWorkflowView v1 = registry.register(tenantId,
                fixture("good/workflow.json"), fixture("good/manifest.json"), null);
        WorkflowRegistry.ComfyWorkflowView v2 = registry.register(tenantId,
                fixture("good/workflow.json"), fixture("good/manifest.json"), null);

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);

        registry.activate(tenantId, v1.id());
        WorkflowRegistry.ComfyWorkflowView activatedV2 = registry.activate(tenantId, v2.id());

        assertThat(activatedV2.status()).isEqualTo("APPROVED");
        assertThat(registry.get(tenantId, v1.id()).orElseThrow().status()).isEqualTo("RETIRED");
        assertThat(registry.active(tenantId, "SCENE")).contains(activatedV2);
    }

    @Test
    void register_bindingToMissingNode_rejectedWithAllErrors() {
        assertThatThrownBy(() -> registry.register(tenantId,
                fixture("missing-node/workflow.json"), fixture("missing-node/manifest.json"), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(422);
                    assertThat(api.getCode()).isEqualTo("WORKFLOW_INVALID");
                    @SuppressWarnings("unchecked")
                    List<String> errors = (List<String>) api.getDetails().get("errors");
                    // inputs.image.node missing, inputs.mask.field missing, params.seed.node missing.
                    assertThat(errors).hasSize(3);
                });
    }

    @Test
    void register_nonCommercialLicense_rejected() {
        assertThatThrownBy(() -> registry.register(tenantId,
                fixture("bad-license/workflow.json"), fixture("bad-license/manifest.json"), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(422);
                    assertThat(api.getCode()).isEqualTo("WORKFLOW_INVALID");
                    @SuppressWarnings("unchecked")
                    List<String> errors = (List<String>) api.getDetails().get("errors");
                    assertThat(errors).anyMatch(e -> e.contains("FLUX-1-dev-Non-Commercial"));
                });
    }

    @Test
    void register_uiFormatJson_rejected() {
        String uiFormat = "{\"nodes\": [{\"id\": 3, \"type\": \"KSampler\", \"inputs\": []}], \"links\": []}";
        assertThatThrownBy(() -> registry.register(tenantId, uiFormat, fixture("good/manifest.json"), null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(422);
                    assertThat(api.getCode()).isEqualTo("WORKFLOW_INVALID");
                    @SuppressWarnings("unchecked")
                    List<String> errors = (List<String>) api.getDetails().get("errors");
                    assertThat(errors).anyMatch(e -> e.contains("API"));
                });
    }

    @Test
    void bootstrap_registersClasspathV1Once() {
        bootstrap.bootstrapAll();
        bootstrap.bootstrapAll();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select code, version, status from comfy_workflow where tenant_id = ? and code = 'CUTOUT'",
                tenantId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("version", 1).containsEntry("status", "APPROVED");
    }

    private static String fixture(String path) {
        try (InputStream in = WorkflowRegistryTest.class.getResourceAsStream("/comfy-fixtures/" + path)) {
            if (in == null) {
                throw new IOException("fixture not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
