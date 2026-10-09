package com.kiano.content.copy;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.content.asset.PrecheckFlag;
import com.kiano.content.asset.PrecheckResult;
import com.kiano.content.facts.FactsJson;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * TextPrecheck (Task 6): unit-normalised fact consistency (Review Focus 3),
 * forbidden claims (global + per-SKU), SEO/copy length caps, price detection
 * with the COPY_WA {{price}} exception, and the policy placeholder.
 */
class TextPrecheckTest {

    private final TextPrecheck precheck = new TextPrecheck(new CopyProperties(), new ObjectMapper());

    private static FactsJson facts() {
        return new FactsJson("MG-KTL17", "Electric Kettles", "1.5L", 350, "220-240V",
                "Stainless steel", "Silver", "1 year", List.of("Kettle", "Base"),
                List.of("Auto shut-off"), List.of(), List.of());
    }

    @Test
    void numbersPresentInFacts_noFlag() {
        PrecheckResult result = precheck.check("COPY_LONG",
                "<p>1500 ml jar with a 350 watts element</p>", facts());
        assertThat(result.flags()).isEmpty();
    }

    @Test
    void inventedNumber_flagsFactMismatch() {
        PrecheckResult result = precheck.check("COPY_LONG",
                "<p>500W heavy-duty element</p>", facts());
        assertThat(result.flags()).contains(PrecheckFlag.FACT_MISMATCH);
        assertThat(result.metrics().get("unmatched")).isEqualTo(List.of("500 W"));
    }

    @Test
    void forbiddenGlobalAndPerSku() {
        FactsJson withSkuClaims = new FactsJson("MG-KTL17", null, null, null, null, null, null,
                null, null, null, null, List.of("quietest in accra"));
        PrecheckResult global = precheck.check("COPY_LONG",
                "This is the best in ghana kettle", facts());
        assertThat(global.flags()).contains(PrecheckFlag.FORBIDDEN_CLAIM);

        PrecheckResult perSku = precheck.check("COPY_LONG",
                "the quietest in accra kettle", withSkuClaims);
        assertThat(perSku.flags()).contains(PrecheckFlag.FORBIDDEN_CLAIM);

        PrecheckResult ok = precheck.check("COPY_LONG",
                "a kettle with auto shut-off", facts());
        assertThat(ok.flags()).doesNotContain(PrecheckFlag.FORBIDDEN_CLAIM);
    }

    @Test
    void seoTooLong_flags() {
        PrecheckResult result = precheck.check("COPY_SEO",
                "{\"title\":\"" + "x".repeat(61) + "\",\"description\":\"" + "y".repeat(156)
                        + "\"}",
                facts());
        assertThat(result.flags()).contains(PrecheckFlag.TOO_LONG);

        PrecheckResult ok = precheck.check("COPY_SEO",
                "{\"title\":\"short\",\"description\":\"also short\"}", facts());
        assertThat(ok.flags()).doesNotContain(PrecheckFlag.TOO_LONG);
    }

    @Test
    void priceInLongCopy_flags_butWaPlaceholderOk() {
        PrecheckResult longCopy = precheck.check("COPY_LONG",
                "Only GH₵ 250 today", facts());
        assertThat(longCopy.flags()).contains(PrecheckFlag.PRICE_IN_COPY);

        PrecheckResult wa = precheck.check("COPY_WA",
                "Hi! Get this kettle for {{price}}", facts());
        assertThat(wa.flags()).doesNotContain(PrecheckFlag.PRICE_IN_COPY);
    }

    @Test
    void waWithoutPlaceholder_flags() {
        PrecheckResult result = precheck.check("COPY_WA", "Hi! Get this kettle today", facts());
        assertThat(result.flags()).contains(PrecheckFlag.MISSING_PLACEHOLDER);
    }

    @Test
    void policyPlaceholder_flagsPolicyPending() {
        PrecheckResult result = precheck.check("COPY_LONG",
                "<!-- POLICY_PENDING -->", facts());
        assertThat(result.flags()).contains(PrecheckFlag.POLICY_PENDING);
    }

    @Test
    void rangeInCopy_matchesFactsRange() {
        PrecheckResult result = precheck.check("COPY_LONG",
                "Works on 220-240V outlets", facts());
        assertThat(result.flags()).doesNotContain(PrecheckFlag.FACT_MISMATCH);
    }

    @Test
    void adCopy_priceOrTooLongHeadline_flagged() {
        com.kiano.content.ads.AdCopyText price = new com.kiano.content.ads.AdCopyText(
                "Save big today", "Now GH₵ 250", "Great kettle");
        PrecheckResult priceResult = precheck.checkAdCopy(price, facts());
        assertThat(priceResult.flags()).contains(PrecheckFlag.PRICE_IN_COPY);

        com.kiano.content.ads.AdCopyText tooLong = new com.kiano.content.ads.AdCopyText(
                "ok", "x".repeat(41), "y");
        PrecheckResult longResult = precheck.checkAdCopy(tooLong, facts());
        assertThat(longResult.flags()).contains(PrecheckFlag.TOO_LONG);
        assertThat(longResult.metrics().get("field")).isEqualTo("headline");

        com.kiano.content.ads.AdCopyText clean = new com.kiano.content.ads.AdCopyText(
                "Auto shut-off", "Boils in minutes", "Fast and safe");
        assertThat(precheck.checkAdCopy(clean, facts()).flags()).isEmpty();
    }

    @Test
    void videoScript_priceTooLongOrFiveCaptions_flagged() {
        com.kiano.content.video.VideoScriptText price = new com.kiano.content.video.VideoScriptText(
                "Hot water fast", List.of("Now only GH₵ 250", "Boils quickly", "Safe"));
        PrecheckResult priceResult = precheck.checkVideoScript(price, facts());
        assertThat(priceResult.flags()).contains(PrecheckFlag.PRICE_IN_COPY);

        com.kiano.content.video.VideoScriptText tooLong = new com.kiano.content.video.VideoScriptText(
                "x".repeat(41), List.of("ok", "also ok", "fine"));
        PrecheckResult longResult = precheck.checkVideoScript(tooLong, facts());
        assertThat(longResult.flags()).contains(PrecheckFlag.TOO_LONG);
        assertThat(longResult.metrics().get("field")).isEqualTo("hook");

        com.kiano.content.video.VideoScriptText fiveCaptions = new com.kiano.content.video.VideoScriptText(
                "short hook", List.of("one", "two", "three", "four", "five"));
        PrecheckResult countResult = precheck.checkVideoScript(fiveCaptions, facts());
        assertThat(countResult.flags()).contains(PrecheckFlag.CAPTION_COUNT);
        assertThat(countResult.metrics().get("field")).isEqualTo("captions");

        com.kiano.content.video.VideoScriptText clean = new com.kiano.content.video.VideoScriptText(
                "Watch it boil", List.of("Auto shut-off", "Stainless steel", "1.5 L"));
        assertThat(precheck.checkVideoScript(clean, facts()).flags()).isEmpty();
    }
}