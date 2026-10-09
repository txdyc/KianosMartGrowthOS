package com.kiano.content.video.h3;

import com.kiano.commerce.ProductView;
import com.kiano.content.template.TemplateRegistry;
import com.kiano.content.video.VideoType;
import com.kiano.platform.web.ApiException;
import com.samskivert.mustache.Mustache;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Deterministic I2VA prompt for the H3 hook: the {@code H3_PROMPT_{TYPE}}
 * template's latest APPROVED version, rendered with the product name, the
 * scene label and the shared clip length. No LLM is involved and both backends
 * use the same prompt (C5 Global Constraints); the template fixes the
 * first-frame instruction line and the three core fields in order.
 */
@Component
public class H3PromptBuilder {

    private final TemplateRegistry templates;
    private final H3Properties properties;

    public H3PromptBuilder(TemplateRegistry templates, H3Properties properties) {
        this.templates = templates;
        this.properties = properties;
    }

    /** Template code of one video type, e.g. {@code H3_PROMPT_DEMO}. */
    public static String code(VideoType type) {
        return "H3_PROMPT_" + type.name();
    }

    public String build(long tenantId, VideoType type, ProductView product, String sceneLabel) {
        String code = code(type);
        String body = templates.latestApproved(tenantId, code)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND",
                        "No approved template for " + code))
                .getBody();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("productName", product.name());
        model.put("sceneLabel", sceneLabel);
        model.put("durationSeconds", properties.getRequestSeconds());
        return Mustache.compiler().escapeHTML(false).compile(body).execute(model);
    }
}