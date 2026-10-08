package com.kiano.content.copy;

import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.asset.AssetService;
import com.kiano.content.asset.PrecheckResult;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactSheetService.FactSheetView;
import com.kiano.content.facts.FactsJson;
import com.kiano.content.policy.PolicyService;
import com.kiano.content.template.TemplateRegistry;
import com.kiano.platform.llm.LlmException;
import com.kiano.platform.llm.LlmGateway;
import com.kiano.platform.llm.LlmPurpose;
import com.kiano.platform.llm.LlmRefusedException;
import com.kiano.platform.llm.LlmRequest;
import com.kiano.platform.llm.LlmResult;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import com.kiano.platform.queue.TaskHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Queue handler for COPY_GENERATE: reads the LOCKED facts, asks Claude for
 * the copy fragments (effort MEDIUM), assembles the six text assets from
 * templates, prechecks each and saves them as IN_REVIEW. With onlySpec only
 * that one spec is regenerated. A stale factVersion skips silently.
 */
@Component
public class CopyGenerationTaskHandler implements TaskHandler {

    public static final String TYPE = "COPY_GENERATE";
    private static final List<String> SPECS = List.of("COPY_TITLE", "COPY_SHORT", "COPY_LONG",
            "COPY_SEO", "COPY_GSHOP", "COPY_WA");

    private final FactSheetService factSheetService;
    private final ProductCatalog productCatalog;
    private final PolicyService policyService;
    private final TemplateRegistry templates;
    private final LlmGateway gateway;
    private final CopyAssembler assembler;
    private final TextPrecheck precheck;
    private final AssetService assetService;

    public CopyGenerationTaskHandler(FactSheetService factSheetService,
            ProductCatalog productCatalog, PolicyService policyService, TemplateRegistry templates,
            LlmGateway gateway, CopyAssembler assembler, TextPrecheck precheck,
            AssetService assetService) {
        this.factSheetService = factSheetService;
        this.productCatalog = productCatalog;
        this.policyService = policyService;
        this.templates = templates;
        this.gateway = gateway;
        this.assembler = assembler;
        this.precheck = precheck;
        this.assetService = assetService;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object handle(TaskContext ctx) throws Exception {
        long tenantId = ctx.tenantId();
        long productId = ctx.payload().path("productId").asLong();
        int factVersion = ctx.payload().path("factVersion").asInt(0);
        String onlySpec = ctx.payload().path("onlySpec").asText("");
        FactSheetView locked = factSheetService.locked(tenantId, productId)
                .orElseThrow(() -> new NonRetryableTaskException(
                        "NO_LOCKED_FACTS: facts must be locked before copy generation"));
        if (locked.version() != factVersion) {
            return Map.of("skipped", "STALE_FACTS");
        }
        Optional<ProductView> product = productCatalog.findById(tenantId, productId);
        if (product.isEmpty()) {
            throw new NonRetryableTaskException("NOT_FOUND: product " + productId + " not found");
        }
        String prompt = templates.latestApproved(tenantId, "COPY_PROMPT")
                .map(t -> t.getBody())
                .orElseThrow(() -> new NonRetryableTaskException(
                        "TEMPLATE_NOT_FOUND: no approved COPY_PROMPT template"));
        String userText = userText(product.get(), locked.facts());
        LlmRequest<CopyDraft> request = new LlmRequest<>(tenantId, LlmPurpose.COPY, prompt,
                userText, List.of(), CopyDraft.class, LlmRequest.Effort.MEDIUM, 16000);
        LlmResult<CopyDraft> result;
        try {
            result = gateway.complete(request);
        } catch (LlmRefusedException ex) {
            throw new NonRetryableTaskException(
                    "LLM_REFUSED" + (ex.category() == null ? "" : " (" + ex.category() + ")")
                            + ": " + ex.getMessage());
        } catch (LlmException ex) {
            if (!ex.retryable()) {
                throw new NonRetryableTaskException(ex.code() + ": " + ex.getMessage());
            }
            throw ex;
        }
        CopyDraft draft = result.output();
        if (draft == null) {
            throw new NonRetryableTaskException("LLM_INVALID_OUTPUT: Claude returned no copy");
        }
        String productName = product.get().name();
        String policyHtml = null;
        Integer policyVersion = null;
        if (policyService.current(tenantId).map(p -> p.complete()).orElse(false)) {
            policyHtml = policyService.renderBlock(tenantId);
            policyVersion = policyService.current(tenantId).get().version();
        }
        Map<String, CopyAssembler.TextAsset> specs = assembler.assemble(tenantId, draft,
                locked.facts(), productName, policyHtml, policyVersion, locked.version());
        List<String> generated = new java.util.ArrayList<>();
        for (Map.Entry<String, CopyAssembler.TextAsset> entry : specs.entrySet()) {
            String spec = entry.getKey();
            if (!onlySpec.isEmpty() && !onlySpec.equals(spec)) {
                continue;
            }
            CopyAssembler.TextAsset text = entry.getValue();
            PrecheckResult check = precheck.check(spec, text.textBody(), locked.facts());
            assetService.createText(tenantId, productId, spec, "default", text,
                    locked.version(), provenance(locked, policyVersion, result, spec), check);
            generated.add(spec);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", generated);
        out.put("llmCallId", result.llmCallId());
        out.put("factVersion", locked.version());
        return out;
    }

    private Map<String, Object> provenance(FactSheetView locked, Integer policyVersion,
            LlmResult<CopyDraft> result, String spec) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("factVersion", locked.version());
        provenance.put("templateCopyPrompt", Map.of("code", "COPY_PROMPT", "version", 1));
        provenance.put("policyVersion", policyVersion);
        provenance.put("model", result.model());
        provenance.put("llmCallId", result.llmCallId());
        provenance.put("spec", spec);
        return provenance;
    }

    private String userText(ProductView product, FactsJson facts) {
        StringBuilder text = new StringBuilder();
        text.append("Product name: ").append(product.name()).append('\n');
        if (product.categorySlugs() != null && !product.categorySlugs().isEmpty()) {
            text.append("WooCommerce category: ")
                    .append(String.join(", ", product.categorySlugs())).append('\n');
        }
        text.append('\n').append("Locked facts:\n");
        append(text, "Model", facts.model());
        append(text, "Category", facts.category());
        append(text, "Capacity", facts.capacity());
        append(text, "Power", facts.powerW() == null ? null : facts.powerW() + " W");
        append(text, "Voltage", facts.voltage());
        append(text, "Material", facts.material());
        append(text, "Colour", facts.colour());
        append(text, "Warranty", facts.warranty());
        if (facts.inBox() != null && !facts.inBox().isEmpty()) {
            text.append("- In the box: ").append(String.join(", ", facts.inBox())).append('\n');
        }
        if (facts.features() != null && !facts.features().isEmpty()) {
            text.append("- Features: ").append(String.join(", ", facts.features())).append('\n');
        }
        return text.toString();
    }

    private static void append(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }
}