package com.kiano.content.ads;

import com.kiano.commerce.ProductCatalog;
import com.kiano.commerce.ProductView;
import com.kiano.content.ContentTier;
import com.kiano.content.asset.AssetService;
import com.kiano.content.asset.PrecheckResult;
import com.kiano.content.copy.CopyAssembler;
import com.kiano.content.copy.TextPrecheck;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactSheetService.FactSheetView;
import com.kiano.content.facts.FactsJson;
import com.kiano.content.profile.ProductProfileService;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Queue handler for AD_COPY_GENERATE: asks the LLM (effort MEDIUM, 8k tokens)
 * for the four hooks' copy and saves one AD_COPY text asset per hook as
 * IN_REVIEW. Non-HERO products and stale fact versions are skipped silently;
 * an output that does not cover all four hooks is LLM_INVALID_OUTPUT. The
 * hook names are the asset variants and the payload hook for single re-runs.
 */
@Component
public class AdCopyTaskHandler implements TaskHandler {

    public static final String TYPE = "AD_COPY_GENERATE";

    private final FactSheetService factSheetService;
    private final ProductCatalog productCatalog;
    private final ProductProfileService profileService;
    private final TemplateRegistry templates;
    private final LlmGateway gateway;
    private final TextPrecheck precheck;
    private final AssetService assetService;

    public AdCopyTaskHandler(FactSheetService factSheetService, ProductCatalog productCatalog,
            ProductProfileService profileService, TemplateRegistry templates,
            LlmGateway gateway, TextPrecheck precheck, AssetService assetService) {
        this.factSheetService = factSheetService;
        this.productCatalog = productCatalog;
        this.profileService = profileService;
        this.templates = templates;
        this.gateway = gateway;
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
        String onlyHook = ctx.payload().path("onlyHook").asText("");
        if (profileService.tierOf(tenantId, productId) != ContentTier.HERO) {
            return Map.of("skipped", "NON_HERO");
        }
        FactSheetView locked = factSheetService.locked(tenantId, productId)
                .orElseThrow(() -> new NonRetryableTaskException(
                        "NO_LOCKED_FACTS: facts must be locked before ad copy generation"));
        if (locked.version() != factVersion) {
            return Map.of("skipped", "STALE_FACTS");
        }
        Optional<ProductView> product = productCatalog.findById(tenantId, productId);
        if (product.isEmpty()) {
            throw new NonRetryableTaskException("NOT_FOUND: product " + productId + " not found");
        }
        String prompt = templates.latestApproved(tenantId, "AD_COPY_PROMPT")
                .map(t -> t.getBody())
                .orElseThrow(() -> new NonRetryableTaskException(
                        "TEMPLATE_NOT_FOUND: no approved AD_COPY_PROMPT template"));
        LlmRequest<AdCopyDraft> request = new LlmRequest<>(tenantId, LlmPurpose.AD_COPY, prompt,
                userText(product.get(), locked.facts()), List.of(), AdCopyDraft.class,
                LlmRequest.Effort.MEDIUM, 8000);
        LlmResult<AdCopyDraft> result;
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
        AdCopyDraft draft = result.output();
        if (draft == null || draft.hooks() == null) {
            throw new NonRetryableTaskException("LLM_INVALID_OUTPUT: ad copy returned no hooks");
        }
        EnumSet<AdHook> covered = EnumSet.noneOf(AdHook.class);
        for (AdCopyDraft.HookCopy copy : draft.hooks()) {
            if (copy != null && copy.hook() != null) {
                covered.add(copy.hook());
            }
        }
        if (!covered.equals(EnumSet.allOf(AdHook.class))) {
            throw new NonRetryableTaskException(
                    "LLM_INVALID_OUTPUT: ad copy must cover all four hooks");
        }
        List<String> generated = new ArrayList<>();
        for (AdCopyDraft.HookCopy copy : draft.hooks()) {
            if (copy == null || copy.hook() == null) {
                continue;
            }
            String hook = copy.hook().wire();
            if (!onlyHook.isEmpty() && !onlyHook.equals(hook)) {
                continue;
            }
            AdCopyText text = AdCopyText.of(copy);
            PrecheckResult check = precheck.checkAdCopy(text, locked.facts());
            Map<String, Object> contentJson = Map.of(
                    "hook", hook, "factVersion", locked.version());
            assetService.createText(tenantId, productId, "AD_COPY", hook,
                    new CopyAssembler.TextAsset(text.toJson(), contentJson), locked.version(),
                    provenance(locked, result, hook), check);
            generated.add(hook);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", generated);
        out.put("llmCallId", result.llmCallId());
        out.put("factVersion", locked.version());
        return out;
    }

    private Map<String, Object> provenance(FactSheetView locked, LlmResult<AdCopyDraft> result,
            String hook) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("factVersion", locked.version());
        provenance.put("templateAdCopyPrompt",
                Map.of("code", "AD_COPY_PROMPT", "version", 1));
        provenance.put("model", result.model());
        provenance.put("llmCallId", result.llmCallId());
        provenance.put("hook", hook);
        return provenance;
    }

    private String userText(ProductView product, FactsJson facts) {
        StringBuilder text = new StringBuilder();
        text.append("Product name: ").append(product.name()).append('\n');
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
        if (facts.benefits() != null && !facts.benefits().isEmpty()) {
            text.append("- Benefits: ").append(String.join(", ", facts.benefits())).append('\n');
        }
        return text.toString();
    }

    private static void append(StringBuilder text, String label, String value) {
        if (value != null && !value.isBlank()) {
            text.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }
}