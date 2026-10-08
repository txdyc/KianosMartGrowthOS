package com.kiano.content.copy;

import com.kiano.content.ads.AdCopyText;
import com.kiano.content.asset.PrecheckFlag;
import com.kiano.content.asset.PrecheckResult;
import com.kiano.content.facts.FactsJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Automatic precheck of generated copy (spec §9.3 / Task 6): numbers must be
 * present in the facts (unit-normalised), forbidden claims are whole-word
 * matches, lengths are capped per spec, prices never appear except the
 * {{price}} placeholder in COPY_WA, and a missing policy block is flagged.
 * HTML tags are stripped before analysis; the text of COPY_SEO is JSON.
 */
@Component
public class TextPrecheck {

    private static final Pattern CURRENCY = Pattern.compile("[₵¢🐻$€£]\\s*\\d|GH₵|GHS|cedi",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");

    private final CopyProperties props;
    private final ObjectMapper objectMapper;

    public TextPrecheck(CopyProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /** Checks one spec's text body against the locked facts. */
    public PrecheckResult check(String specCode, String text, FactsJson facts) {
        List<PrecheckFlag> flags = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        String plain = TAG.matcher(text == null ? "" : text).replaceAll(" ");
        Set<QuantityNormalizer.Quantity> copyQuantities = QuantityNormalizer.extract(plain);
        Set<QuantityNormalizer.Quantity> factQuantities = factQuantities(facts);
        List<String> unmatched = copyQuantities.stream()
                .filter(q -> !factQuantities.contains(q))
                .map(q -> q.value().toPlainString() + " " + q.unit())
                .toList();
        if (!unmatched.isEmpty()) {
            flags.add(PrecheckFlag.FACT_MISMATCH);
            metrics.put("unmatched", unmatched);
        }
        for (String claim : forbiddenClaims(facts)) {
            if (containsWord(plain, claim)) {
                flags.add(PrecheckFlag.FORBIDDEN_CLAIM);
                break;
            }
        }
        if (tooLong(specCode, text)) {
            flags.add(PrecheckFlag.TOO_LONG);
        }
        if (!"COPY_WA".equals(specCode) && CURRENCY.matcher(plain).find()) {
            flags.add(PrecheckFlag.PRICE_IN_COPY);
        }
        if ("COPY_WA".equals(specCode) && !text.contains("{{price}}")) {
            flags.add(PrecheckFlag.MISSING_PLACEHOLDER);
        }
        if ("COPY_LONG".equals(specCode) && text.contains("POLICY_PENDING")) {
            flags.add(PrecheckFlag.POLICY_PENDING);
        }
        return new PrecheckResult(List.copyOf(flags), metrics);
    }

    /**
     * Precheck for one hook's ad copy: the shared fact/claim/price rules plus
     * per-field length caps (overlay/headline 40, primaryText 125); the too-long
     * flag records which field overflowed in metrics.field.
     */
    public PrecheckResult checkAdCopy(AdCopyText text, FactsJson facts) {
        List<PrecheckFlag> flags = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        String plain = TAG.matcher((text.overlay() == null ? "" : text.overlay()) + " "
                + (text.headline() == null ? "" : text.headline()) + " "
                + (text.primaryText() == null ? "" : text.primaryText())).replaceAll(" ");
        Set<QuantityNormalizer.Quantity> copyQuantities = QuantityNormalizer.extract(plain);
        Set<QuantityNormalizer.Quantity> factQuantities = factQuantities(facts);
        List<String> unmatched = copyQuantities.stream()
                .filter(q -> !factQuantities.contains(q))
                .map(q -> q.value().toPlainString() + " " + q.unit())
                .toList();
        if (!unmatched.isEmpty()) {
            flags.add(PrecheckFlag.FACT_MISMATCH);
            metrics.put("unmatched", unmatched);
        }
        for (String claim : forbiddenClaims(facts)) {
            if (containsWord(plain, claim)) {
                flags.add(PrecheckFlag.FORBIDDEN_CLAIM);
                break;
            }
        }
        if (CURRENCY.matcher(plain).find()) {
            flags.add(PrecheckFlag.PRICE_IN_COPY);
        }
        String field = null;
        if (text.overlay() != null && text.overlay().length() > 40) {
            field = "overlay";
        } else if (text.headline() != null && text.headline().length() > 40) {
            field = "headline";
        } else if (text.primaryText() != null && text.primaryText().length() > 125) {
            field = "primaryText";
        }
        if (field != null) {
            flags.add(PrecheckFlag.TOO_LONG);
            metrics.put("field", field);
        }
        return new PrecheckResult(List.copyOf(flags), metrics);
    }

    private List<String> forbiddenClaims(FactsJson facts) {
        List<String> claims = new ArrayList<>(props.getForbiddenClaims());
        if (facts.forbiddenClaims() != null) {
            claims.addAll(facts.forbiddenClaims());
        }
        return claims;
    }

    private static boolean containsWord(String text, String word) {
        String lower = text.toLowerCase(Locale.ROOT);
        String target = word.toLowerCase(Locale.ROOT);
        int index = lower.indexOf(target);
        while (index >= 0) {
            boolean beforeBoundary = index == 0 || !Character.isLetterOrDigit(lower.charAt(index - 1));
            int end = index + target.length();
            boolean afterBoundary = end >= lower.length()
                    || !Character.isLetterOrDigit(lower.charAt(end));
            if (beforeBoundary && afterBoundary) {
                return true;
            }
            index = lower.indexOf(target, index + 1);
        }
        return false;
    }

    private boolean tooLong(String specCode, String text) {
        switch (specCode) {
            case "COPY_TITLE" -> {
                return text != null && text.length() > props.getMaxTitle();
            }
            case "COPY_GSHOP" -> {
                return text != null && text.length() > props.getMaxGshop();
            }
            case "COPY_SEO" -> {
                JsonNode seo = objectMapper.readTree(text);
                String title = seo.path("title").asText("");
                String description = seo.path("description").asText("");
                return title.length() > props.getMaxSeoTitle()
                        || description.length() > props.getMaxSeoDescription();
            }
            default -> {
                return false;
            }
        }
    }

    /** Every Quantity across the facts' own text fields, plus powerW as watts. */
    private Set<QuantityNormalizer.Quantity> factQuantities(FactsJson facts) {
        Set<QuantityNormalizer.Quantity> quantities = new LinkedHashSet<>();
        if (facts == null) {
            return quantities;
        }
        List<String> texts = new ArrayList<>();
        texts.add(facts.model());
        texts.add(facts.category());
        texts.add(facts.capacity());
        texts.add(facts.voltage());
        texts.add(facts.material());
        texts.add(facts.colour());
        texts.add(facts.warranty());
        addAll(texts, facts.inBox());
        addAll(texts, facts.features());
        addAll(texts, facts.benefits());
        for (String text : texts) {
            if (text != null) {
                quantities.addAll(QuantityNormalizer.extract(text));
            }
        }
        if (facts.powerW() != null) {
            quantities.add(new QuantityNormalizer.Quantity(
                    java.math.BigDecimal.valueOf(facts.powerW()), "W"));
        }
        return quantities;
    }

    private static void addAll(List<String> target, List<String> values) {
        if (values != null) {
            target.addAll(values);
        }
    }
}