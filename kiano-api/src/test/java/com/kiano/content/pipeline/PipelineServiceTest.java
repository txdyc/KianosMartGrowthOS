package com.kiano.content.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.generation.GenerationJob;
import com.kiano.content.generation.GenerationJobStore;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.web.ApiException;
import com.kiano.workerprotocol.ExecutorType;
import com.kiano.workerprotocol.JobStep;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * PipelineService.start: shot selection, downstream planning and the guard
 * clauses (SHOTS_NOT_READY, PIPELINE_RUNNING, WORKFLOW_NOT_ACTIVE).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PipelineServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GenerationJobStore jobs;

    @Autowired
    private PipelineService pipeline;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;
    private long storeId;
    private long userId;
    private CurrentUser user;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from generation_job");
        jdbcTemplate.update("delete from generation_run");
        jdbcTemplate.update("delete from comfy_workflow");
        jdbcTemplate.update("delete from product_profile");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'pipeline-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        user = new CurrentUser(userId, tenantId, Role.OPERATOR, "pipeline-op@example.test");
        activateWorkflow("CUTOUT");
        activateWorkflow("SCENE");
    }

    @Test
    void start_createsCutoutsForAcceptedShotsOnly() {
        long pid = product("MG-BL200", "Kettle 1.7L");
        for (String code : List.of("P1", "P2", "P4", "P5", "P6", "P7", "P8")) {
            media(pid, code, "ACCEPTED");
        }
        media(pid, "P3", "RESHOOT");

        long runId = pipeline.start(user, pid);

        List<GenerationJob> created = jobs.findByRun(tenantId, runId);
        assertThat(created).hasSize(5);
        assertThat(created).allMatch(job -> job.step() == JobStep.CUTOUT);
        List<String> shots = created.stream()
                .map(job -> job.input().path("shotCode").asString()).toList();
        // P3 is RESHOOT and P5/P7 are not angle shots -> no cutout.
        assertThat(shots).containsExactlyInAnyOrder("P1", "P2", "P4", "P6", "P8");

        GenerationJob p1 = created.stream()
                .filter(job -> job.input().path("shotCode").asString().equals("P1")).findFirst()
                .orElseThrow();
        assertThat(p1.executor()).isEqualTo(ExecutorType.COMFYUI);
        assertThat(p1.variant()).isEqualTo("P1");
        assertThat(p1.input().path("workflow").path("code").asString()).isEqualTo("CUTOUT");
        assertThat(p1.input().path("inputs").path("image").asString())
                .isEqualTo("t" + tenantId + "/source-media/" + pid + "/P1.png");
        // The output key embeds the job id, so input_json is back-filled after insert.
        assertThat(p1.input().path("outputs").path("cutout").asString())
                .isEqualTo("t" + tenantId + "/gen/" + p1.id() + "/cutout.png");
        JsonNode downstream = p1.input().path("downstream");
        assertThat(downstream.get(0).path("step").asString()).isEqualTo("WHITE_MAIN");
        assertThat(downstream.get(0).path("variant").asString()).isEqualTo("main");
        assertThat(downstream).hasSize(1);

        // Standard tier: the scene source is P2 -> 2 SCENE_INPUT entries downstream.
        GenerationJob p2 = created.stream()
                .filter(job -> job.input().path("shotCode").asString().equals("P2")).findFirst()
                .orElseThrow();
        List<String> p2Steps = new java.util.ArrayList<>();
        p2.input().path("downstream").forEach(d -> p2Steps.add(d.path("step").asString()));
        assertThat(p2Steps).containsExactly("WHITE_ANGLE", "SCENE_INPUT", "SCENE_INPUT");

        // The run itself is RUNNING.
        assertThat(jdbcTemplate.queryForObject(
                "select status from generation_run where id = ?", String.class, runId))
                .isEqualTo("RUNNING");
    }

    @Test
    void start_p2AngleAndSceneSource_singleCutoutWithMergedDownstream() {
        long pid = product("MG-FAN16", "Standing Fan 16in");
        jdbcTemplate.update(
                "insert into product_profile (product_id, tenant_id, role_source, content_tier) "
                        + "values (?, ?, 'MANUAL', 'HERO')", pid, tenantId);
        for (String code : List.of("P1", "P2", "P3", "P4", "P6", "P8")) {
            media(pid, code, "ACCEPTED");
        }

        long runId = pipeline.start(user, pid);

        List<GenerationJob> created = jobs.findByRun(tenantId, runId);
        assertThat(created).hasSize(6); // P1, P2, P3, P4, P6, P8 - one cutout each
        GenerationJob p2 = created.stream()
                .filter(job -> job.input().path("shotCode").asString().equals("P2")).findFirst()
                .orElseThrow();
        // HERO: P2 is both an angle shot and the scene source -> merged downstream.
        List<String> steps = new java.util.ArrayList<>();
        List<String> variants = new java.util.ArrayList<>();
        p2.input().path("downstream").forEach(d -> {
            steps.add(d.path("step").asString());
            variants.add(d.path("variant").asString());
        });
        assertThat(steps).containsExactly("WHITE_ANGLE", "SCENE_INPUT", "SCENE_INPUT",
                "SCENE_INPUT", "SCENE_INPUT");
        assertThat(variants).containsExactly("P2", "scene1", "scene2", "scene3", "scene4");

        GenerationJob p1 = created.stream()
                .filter(job -> job.input().path("shotCode").asString().equals("P1")).findFirst()
                .orElseThrow();
        assertThat(p1.input().path("downstream")).hasSize(1);
        assertThat(p1.input().path("downstream").get(0).path("step").asString())
                .isEqualTo("WHITE_MAIN");
    }

    @Test
    void start_missingP1_422() {
        long pid = product("MG-BL200", "Kettle 1.7L");
        media(pid, "P2", "ACCEPTED");

        assertThatThrownBy(() -> pipeline.start(user, pid))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("SHOTS_NOT_READY");
                    assertThat(ex.getDetails().get("missing")).isEqualTo(List.of("P1"));
                });
    }

    @Test
    void start_whileRunning_409() {
        long pid = product("MG-BL200", "Kettle 1.7L");
        for (String code : List.of("P1", "P2", "P3", "P4", "P6", "P8")) {
            media(pid, code, "ACCEPTED");
        }
        pipeline.start(user, pid); // leaves QUEUED cutouts -> RUNNING

        assertThatThrownBy(() -> pipeline.start(user, pid))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("PIPELINE_RUNNING");
                });
    }

    @Test
    void start_noActiveWorkflow_409() {
        jdbcTemplate.update("delete from comfy_workflow where code = 'SCENE'");
        long pid = product("MG-BL200", "Kettle 1.7L");
        for (String code : List.of("P1", "P2", "P3", "P4", "P6", "P8")) {
            media(pid, code, "ACCEPTED");
        }

        assertThatThrownBy(() -> pipeline.start(user, pid))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("WORKFLOW_NOT_ACTIVE");
                    assertThat(ex.getDetails().get("code")).isEqualTo("SCENE");
                });
    }

    private void activateWorkflow(String code) {
        jdbcTemplate.update(
                "insert into comfy_workflow (tenant_id, code, version, workflow_json, manifest_json, "
                        + "model_refs, status) values (?, ?, 1, '{}', ?, '[]', 'APPROVED')",
                tenantId, code, "{\"code\":\"" + code + "\"}");
    }

    private long product(String sku, String name) {
        return jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, ?, 'simple', ?, ?, 'publish', now()) returning id",
                Long.class, tenantId, storeId, -(System.nanoTime() / 1000), sku, name);
    }

    private void media(long productId, String shotCode, String status) {
        jdbcTemplate.update(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, thumb_object_key, content_type, size_bytes, sha256, qc_json, status) "
                        + "values (?, ?, ?, 'PHOTO', ?, ?, null, 'image/jpeg', 1024, ?, '{}', ?)",
                tenantId, productId, shotCode, "MG-X_" + shotCode + ".jpg",
                "t" + tenantId + "/source-media/" + productId + "/" + shotCode + ".png",
                shotCode.hashCode() + "0".repeat(58), status);
    }
}
