package com.kiano.content.facts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.facts.FactSheetService.FactSheetView;
import com.kiano.content.template.TemplateRegistry;
import com.kiano.imaging.ImageCodec;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.llm.FakeLlmGateway;
import com.kiano.platform.llm.LlmException;
import com.kiano.platform.llm.LlmImage;
import com.kiano.platform.llm.LlmPurpose;
import com.kiano.platform.llm.LlmRefusedException;
import com.kiano.platform.llm.LlmRequest;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import com.kiano.platform.storage.ObjectStorage;
import jakarta.servlet.http.Cookie;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Fact draft from Claude: the 202 enqueue + G2 draft creation, P5/PROMO
 * image downscaling (long side ≤ 1568, JPEG), FACT_PROMPT usage, refusal →
 * non-retryable failure without a draft, and 409/422 guards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, FactDraftTaskHandlerTest.FakeGatewayConfig.class})
class FactDraftTaskHandlerTest {

    private static final String OP_EMAIL = "draft-op@example.test";
    private static final String OP_PASSWORD = "op-pass-123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private FakeLlmGateway gateway;

    @Autowired
    private FactDraftTaskHandler handler;

    @Autowired
    private FactSheetService factSheetService;

    @Autowired
    private TemplateRegistry templates;

    @Autowired
    private ObjectMapper mapper;

    private long tenantId;
    private long storeId;
    private long userId;
    private long productId;
    private long p5Id;
    private long promoId;

    @TestConfiguration
    static class FakeGatewayConfig {
        @Bean
        @Primary
        FakeLlmGateway fakeLlmGateway() {
            return new FakeLlmGateway();
        }
    }

    @BeforeEach
    void seed() throws Exception {
        gateway.reset();
        jdbcTemplate.update("delete from platform_task where tenant_id = "
                + "(select id from tenant where slug = 'kianosmart')");
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from llm_call");
        jdbcTemplate.update("delete from source_media");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        long callId = jdbcTemplate.queryForObject(
                "insert into llm_call (tenant_id, purpose, model, input_tokens, output_tokens, cost_usd, "
                        + "latency_ms, status) values (?, 'FACT_DRAFT', 'claude-opus-5-5', 100, 50, "
                        + "0.001400, 42, 'OK') returning id",
                Long.class, tenantId);
        gateway.setLlmCallId(callId);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, -4000, 'simple', 'MG-KTL17', 'Kettle 1.7L', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
        userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, ?, 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, OP_EMAIL, passwordEncoder.encode(OP_PASSWORD));
        p5Id = sourceMedia("P5", "PHOTO", 2000, 3000);
        promoId = sourceMedia("PROMO", "PROMO_IMAGE", 3467, 2600);
    }

    private long sourceMedia(String shotCode, String kind, int w, int h) throws Exception {
        String objectKey = "t" + tenantId + "/source-media/" + productId + "/" + shotCode + ".jpg";
        storage.put(objectKey, jpeg(w, h), "image/jpeg");
        return jdbcTemplate.queryForObject(
                "insert into source_media (tenant_id, product_id, shot_code, kind, original_file_name, "
                        + "object_key, thumb_object_key, content_type, size_bytes, sha256, qc_json, status) "
                        + "values (?, ?, ?, ?, ?, ?, null, 'image/jpeg', ?, ?, '{}', 'ACCEPTED') "
                        + "returning id",
                Long.class, tenantId, productId, shotCode, kind, shotCode + ".jpg", objectKey,
                w * h, "sha" + shotCode);
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void draftFromAi_enqueues_202_thenHandlerCreatesDraftWithSources() throws Exception {
        String token = login();
        gateway.queue(sampleDraft());

        MvcResult enqueue = mockMvc.perform(post("/api/v1/content/products/" + productId
                        + "/facts/draft-from-ai").cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").exists())
                .andReturn();
        JsonNode result = mapper.readTree(enqueue.getResponse().getContentAsString());
        assertThat(result.path("alreadyQueued").asBoolean()).isFalse();

        handler.handle(ctx(result.path("taskId").asLong()));

        FactSheetView draft = factSheetService.current(tenantId, productId).get();
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.facts().model()).isEqualTo("MG-KTL17");
        assertThat(jdbcTemplate.queryForList(
                "select id from platform_task where tenant_id = ? and type = 'FACT_DRAFT'",
                Long.class, tenantId)).hasSize(1);
    }

    @Test
    void handler_sendsP5AndPromoImagesDownscaled_andFactPromptVersion() throws Exception {
        gateway.queue(sampleDraft());

        Object out = handler.handle(ctx(99L));

        Map<?, ?> result = (Map<?, ?>) out;
        FactSheetView draft = factSheetService.current(tenantId, productId).get();
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.facts().model()).isEqualTo("MG-KTL17");
        assertThat(draft.facts().powerW()).isEqualTo(2000);
        assertThat(draft.fieldSources()).containsEntry("model", FieldSource.P5);
        assertThat(draft.sourceMediaIds()).containsExactly(p5Id, promoId);
        assertThat(result.get("version")).isEqualTo(1);

        FakeLlmGateway.RecordedRequest request = gateway.last();
        assertThat(request.purpose()).isEqualTo(LlmPurpose.FACT_DRAFT);
        assertThat(request.effort()).isEqualTo(LlmRequest.Effort.HIGH);
        assertThat(request.maxTokens()).isEqualTo(16000);
        assertThat(request.outputType()).isEqualTo(FactDraftResult.class);
        assertThat(request.images()).hasSize(2);
        for (LlmImage image : request.images()) {
            BufferedImage decoded = ImageCodec.read(image.jpeg());
            assertThat(Math.max(decoded.getWidth(), decoded.getHeight()))
                    .isLessThanOrEqualTo(1568);
        }
        String promptBody = templates.latestApproved(tenantId, "FACT_PROMPT").get().getBody();
        assertThat(request.system()).isEqualTo(promptBody);
        assertThat(request.userText()).contains("Kettle 1.7L");
    }

    @Test
    void noP5NorPromo_422() throws Exception {
        jdbcTemplate.update("delete from source_media");
        String token = login();
        mockMvc.perform(post("/api/v1/content/products/" + productId + "/facts/draft-from-ai")
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_FACT_SOURCES"));
    }

    @Test
    void existingDraft_409() throws Exception {
        factSheetService.saveDraft(new CurrentUser(userId, tenantId, Role.OPERATOR, OP_EMAIL),
                productId, sampleDraft().facts(), sampleDraft().sources());
        String token = login();
        mockMvc.perform(post("/api/v1/content/products/" + productId + "/facts/draft-from-ai")
                        .cookie(new Cookie("kiano_token", token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FACT_DRAFT_EXISTS"));
    }

    @Test
    void refusal_failsTaskNonRetryable_noDraftCreated() {
        gateway.failWith(new LlmRefusedException("I refuse", "unsafe"));

        assertThatThrownBy(() -> handler.handle(ctx(1L)))
                .isInstanceOfSatisfying(NonRetryableTaskException.class,
                        ex -> assertThat(ex.getMessage()).startsWith("LLM_REFUSED"));
        assertThat(factSheetService.current(tenantId, productId)).isEmpty();
    }

    @Test
    void truncatedOrInvalid_noPartialDraftPersisted() {
        gateway.queue(new FactDraftResult(null, Map.of(), List.of()));

        assertThatThrownBy(() -> handler.handle(ctx(2L)))
                .isInstanceOf(NonRetryableTaskException.class)
                .hasMessageContaining("LLM_INVALID_OUTPUT");
        assertThat(factSheetService.current(tenantId, productId)).isEmpty();
    }

    private FactDraftResult sampleDraft() {
        FactsJson facts = new FactsJson("MG-KTL17", "Electric Kettles", "1.7 L", 2000,
                "220-240V", "Stainless steel", "Silver", "1 year", List.of("Kettle", "Base"),
                List.of("Auto shut-off"), List.of(), List.of());
        return new FactDraftResult(facts,
                Map.of("model", FieldSource.P5, "capacity", FieldSource.P5,
                        "powerW", FieldSource.P5, "voltage", FieldSource.P5,
                        "warranty", FieldSource.WOO_TEXT, "inBox", FieldSource.PROMO),
                List.of("material"));
    }

    private TaskContext ctx(long taskId) {
        return new TaskContext(taskId, tenantId,
                mapper.createObjectNode().put("productId", productId), 1);
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + OP_EMAIL + "\",\"password\":\"" + OP_PASSWORD
                                + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        return setCookie.substring("kiano_token=".length(), setCookie.indexOf(';'));
    }
}