package com.kiano.content.copy;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * Structured output of the copy-generation LLM call. Constraints are spelled
 * out in the field descriptions so the JSON schema carries them: title
 * format, bullet/FAQ counts, SEO lengths, GShop order and the {{price}}
 * placeholder rule (never a real price number).
 */
public record CopyDraft(
        @JsonPropertyDescription("Product title exactly in the format: Morgan {model} {category} {key spec} - {core benefit}") String title,
        @JsonPropertyDescription("3 to 5 short selling points") List<String> shortBullets,
        @JsonPropertyDescription("3 to 5 paragraphs of 'why buy' reasoning") List<String> whyBuy,
        @JsonPropertyDescription("3 to 5 frequently asked questions") List<Faq> faq,
        @JsonPropertyDescription("SEO title, at most 60 characters") String seoTitle,
        @JsonPropertyDescription("SEO meta description, at most 155 characters") String seoDescription,
        @JsonPropertyDescription("Google Shopping title with brand, category and key attributes first") String gshopTitle,
        @JsonPropertyDescription("WhatsApp message. MUST contain the literal placeholder {{price}}; never write an actual price number anywhere") String waMessage) {

    public record Faq(String q, String a) {
    }
}