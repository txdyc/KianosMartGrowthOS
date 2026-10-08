package com.kiano.content.publish;

import com.kiano.content.asset.ReviewService.ReviewItem;

/**
 * Alt text for each gallery image: "{product name} – {view}" with the view
 * fixed per spec/variant (spec §10.1 uploads).
 */
public final class AltTextBuilder {

    private AltTextBuilder() {
    }

    public static String build(String productName, ReviewItem item) {
        String view = switch (item.specCode()) {
            case "PAGE_MAIN" -> "front view";
            case "PAGE_ANGLE" -> switch (item.variant()) {
                case "P2" -> "front-left view";
                case "P3" -> "front-right view";
                case "P4" -> "side view";
                case "P6" -> "top view";
                default -> item.variant();
            };
            case "PAGE_SCENE" -> "in a Ghanaian home";
            case "PAGE_INBOX" -> "what's in the box";
            case "PAGE_INFO" -> "key features";
            case "PAGE_SPEC" -> "specifications";
            default -> item.specCode();
        };
        return productName + " – " + view;
    }
}