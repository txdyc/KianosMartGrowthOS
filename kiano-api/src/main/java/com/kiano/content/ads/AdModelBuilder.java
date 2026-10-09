package com.kiano.content.ads;

import com.kiano.commerce.ProductView;
import com.kiano.content.ads.AdBaseSelector.BaseImage;
import com.kiano.content.policy.PolicySection;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Builds the JMustache model of an AD_* overlay template. Only the overlay /
 * headline / primary text come from the approved AD_COPY asset; price and the
 * Ends label come from {@link PriceDisplay} (data), the trust badges from the
 * policy settings plus the locked-facts warranty. The base JPEG is inlined as
 * a data URI so the render makes no network requests.
 */
@Component
public class AdModelBuilder {

    /** Trust badge order: the three policy sections, then the warranty. */
    private static final List<PolicySection> BADGE_SECTIONS =
            List.of(PolicySection.COD, PolicySection.MOMO, PolicySection.DELIVERY);

    public Map<String, Object> build(ProductView product, AdHook hook, AdSize size,
            BaseImage base, AdCopyText copy, PriceDisplay price,
            Map<PolicySection, String> badges, @Nullable String warranty, double fontScale) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("width", size.width());
        model.put("height", size.height());
        model.put("sizeClass", size.sizeClass());
        model.put("baseImage", "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(base.jpeg()));
        model.put("overlay", copy.overlay() == null ? "" : copy.overlay());
        model.put("productName", product.name());
        model.put("fontScale", fontScale);
        if (hook == AdHook.PRICEHOOK) {
            model.put("current", price.current());
            if (price.strike() != null) {
                model.put("strike", price.strike());
            }
            if (price.endsLabel() != null) {
                model.put("endsLabel", price.endsLabel());
            }
        }
        if (hook == AdHook.TRUST) {
            List<Map<String, String>> badgeTexts = new ArrayList<>();
            for (PolicySection section : BADGE_SECTIONS) {
                String text = badges == null ? null : badges.get(section);
                if (text != null && !text.isBlank()) {
                    badgeTexts.add(Map.of("text", text));
                }
            }
            if (warranty != null && !warranty.isBlank()) {
                badgeTexts.add(Map.of("text", warranty + " warranty"));
            }
            model.put("badges", badgeTexts);
        }
        return model;
    }
}
