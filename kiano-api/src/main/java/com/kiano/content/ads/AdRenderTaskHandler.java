package com.kiano.content.ads;

import com.kiano.content.ads.AdRenderService.RenderOutcome;
import com.kiano.platform.queue.NonRetryableTaskException;
import com.kiano.platform.queue.TaskContext;
import com.kiano.platform.queue.TaskHandler;
import com.kiano.platform.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Queue handler for AD_RENDER: renders the requested (or all twelve) AD_STATIC
 * assets through {@link AdRenderService}. Business preconditions surface as a
 * non-retryable task error so the queue does not keep retrying a render that
 * can never succeed.
 */
@Component
public class AdRenderTaskHandler implements TaskHandler {

    public static final String TYPE = "AD_RENDER";

    private final AdRenderService renderService;

    public AdRenderTaskHandler(AdRenderService renderService) {
        this.renderService = renderService;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object handle(TaskContext ctx) {
        long tenantId = ctx.tenantId();
        long productId = ctx.payload().path("productId").asLong();
        int frameCandidate = ctx.payload().path("frameCandidate").asInt(0);
        boolean priceOnly = ctx.payload().path("priceOnly").asBoolean(false);
        List<String> variants = null;
        JsonNode variantsNode = ctx.payload().path("variants");
        if (variantsNode.isArray() && !variantsNode.isEmpty()) {
            variants = new ArrayList<>();
            for (int i = 0; i < variantsNode.size(); i++) {
                variants.add(variantsNode.get(i).asText());
            }
        }
        Map<String, Long> autoApproveFrom = null;
        JsonNode autoApproveNode = ctx.payload().path("autoApproveFrom");
        if (autoApproveNode.isObject() && !autoApproveNode.isEmpty()) {
            autoApproveFrom = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : autoApproveNode.properties()) {
                autoApproveFrom.put(entry.getKey(), entry.getValue().asLong());
            }
        }
        try {
            RenderOutcome outcome = renderService.renderAll(tenantId, productId, variants,
                    frameCandidate, priceOnly, autoApproveFrom);
            return outcome;
        } catch (ApiException ex) {
            if (ex.getStatus().is4xxClientError()) {
                throw new NonRetryableTaskException(ex.getCode() + ": " + ex.getMessage());
            }
            throw ex;
        }
    }
}
