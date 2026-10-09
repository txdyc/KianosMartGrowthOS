package com.kiano.content.video;

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
 * Queue handler for VIDEO_SCRIPT_GENERATE: asks the LLM (effort MEDIUM, 8k
 * tokens) for the three video types' scripts and saves one VIDEO_SCRIPT text
 * asset per type as IN_REVIEW. Non-HERO products and stale fact versions are
 * skipped silently; an output that does not cover all three types is
 * LLM_INVALID_OUTPUT. The type names are the asset variants and the payload
 * hook for single re-runs.
 */
@Component
public class VideoScriptTaskHandler implements TaskHandler {

    public static final String TYPE = "VIDEO_SCRIPT_GENERATE";

    private final FactSheetService factSheetService;
    private final ProductCatalog productCatalog;
    private final ProductProfileService profileService;
    private final TemplateRegistry templates;
    private final LlmGateway gateway;
    private final TextPrecheck precheck;
    private final AssetService assetService;

    public VideoScriptTaskHandler(FactSheetService factSheetService, ProductCatalog productCatalog,
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
        String onlyType = ctx.payload().path("onlyType").asText("");
        if (profileService.tierOf(tenantId, productId) != ContentTier.HERO) {
            return Map.of("skipped", "NON_HERO");
        }
        FactSheetView locked = factSheetService.locked(tenantId, productId)
                .orElseThrow(() -> new NonRetryableTaskException(
                        "NO_LOCKED_FACTS: facts must be locked before video script generation"));
        if (locked.version() != factVersion) {
            return Map.of("skipped", "STALE_FACTS");
        }
        Optional<ProductView> product = productCatalog.findById(tenantId, productId);
        if (product.isEmpty()) {
            throw new NonRetryableTaskException("NOT_FOUND: product " + productId + " not found");
        }
        String prompt = templates.latestApproved(tenantId, "VIDEO_SCRIPT_PROMPT")
                .map(t -> t.getBody())
                .orElseThrow(() -> new NonRetryableTaskException(
                        "TEMPLATE_NOT_FOUND: no approved VIDEO_SCRIPT_PROMPT template"));
        LlmRequest<VideoScriptDraft> request = new LlmRequest<>(tenantId,
                LlmPurpose.VIDEO_SCRIPT, prompt, userText(product.get(), locked.facts()),
                List.of(), VideoScriptDraft.class, LlmRequest.Effort.MEDIUM, 8000);
        LlmResult<VideoScriptDraft> result;
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
        VideoScriptDraft draft = result.output();
        if (draft == null || draft.videos() == null) {
            throw new NonRetryableTaskException(
                    "LLM_INVALID_OUTPUT: video script returned no videos");
        }
        EnumSet<VideoType> covered = EnumSet.noneOf(VideoType.class);
        for (VideoScriptDraft.VideoScript video : draft.videos()) {
            if (video != null && video.type() != null) {
                covered.add(video.type());
            }
        }
        if (!covered.equals(EnumSet.allOf(VideoType.class))) {
            throw new NonRetryableTaskException(
                    "LLM_INVALID_OUTPUT: video script must cover all three types");
        }
        List<String> generated = new ArrayList<>();
        for (VideoScriptDraft.VideoScript video : draft.videos()) {
            if (video == null || video.type() == null) {
                continue;
            }
            String type = video.type().wire();
            if (!onlyType.isEmpty() && !onlyType.equals(type)) {
                continue;
            }
            VideoScriptText text = VideoScriptText.of(video);
            PrecheckResult check = precheck.checkVideoScript(text, locked.facts());
            Map<String, Object> contentJson = Map.of(
                    "type", type, "factVersion", locked.version());
            assetService.createText(tenantId, productId, "VIDEO_SCRIPT", type,
                    new CopyAssembler.TextAsset(text.toJson(), contentJson), locked.version(),
                    provenance(locked, result, type), check);
            generated.add(type);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generated", generated);
        out.put("llmCallId", result.llmCallId());
        out.put("factVersion", locked.version());
        return out;
    }

    private Map<String, Object> provenance(FactSheetView locked, LlmResult<VideoScriptDraft> result,
            String type) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("factVersion", locked.version());
        provenance.put("templateVideoScriptPrompt",
                Map.of("code", "VIDEO_SCRIPT_PROMPT", "version", 1));
        provenance.put("model", result.model());
        provenance.put("llmCallId", result.llmCallId());
        provenance.put("type", type);
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
