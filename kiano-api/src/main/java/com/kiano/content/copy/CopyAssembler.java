package com.kiano.content.copy;

import com.kiano.content.facts.FactsJson;
import com.kiano.content.template.TemplateRegistry;
import com.kiano.platform.web.ApiException;
import com.samskivert.mustache.Mustache;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a {@link CopyDraft} plus the locked facts into the six text assets.
 * The deterministic parts - spec table, bullet list, keyword JSON, policy
 * block - are assembled from templates and facts; every LLM fragment is
 * HTML-escaped by JMustache's default {{ }} and never via {{{ }}. Trusted
 * policy HTML is spliced into COPY_LONG by replacing the empty policy slot
 * after rendering, so templates never use triple braces.
 */
@Component
public class CopyAssembler {

    /** One text asset: body + structured source for re-rendering. */
    public record TextAsset(String textBody, Map<String, Object> contentJson) {
    }

    /** Marker in COPY_LONG replaced by the rendered policy block. */
    private static final String POLICY_SLOT = "<div class=\"kiano-policy-slot\"></div>";

    private final TemplateRegistry templates;
    private final ObjectMapper objectMapper;

    public CopyAssembler(TemplateRegistry templates, ObjectMapper objectMapper) {
        this.templates = templates;
        this.objectMapper = objectMapper;
    }

    /** Assembles all six specs. factVersion/policyVersion live in contentJson. */
    public Map<String, TextAsset> assemble(long tenantId, CopyDraft draft, FactsJson facts,
            String productName, @Nullable String policyHtml, @Nullable Integer policyVersion,
            int factVersion) {
        Map<String, TextAsset> out = new LinkedHashMap<>();
        // Plain-text specs lose any markup the model produced (see PlainText).
        String title = PlainText.strip(draft.title());
        String seoTitle = PlainText.strip(draft.seoTitle());
        String seoDescription = PlainText.strip(draft.seoDescription());
        String gshopTitle = PlainText.strip(draft.gshopTitle());
        String waMessage = PlainText.strip(draft.waMessage());
        Map<String, Object> base = baseContent(draft, facts, factVersion, policyVersion);
        out.put("COPY_TITLE", new TextAsset(title, with(base, Map.of("title", title))));
        out.put("COPY_SHORT", new TextAsset(renderShort(tenantId, draft), with(base, Map.of(
                "shortBullets", draft.shortBullets() == null ? List.of() : draft.shortBullets()))));
        String longBody = renderLong(tenantId, draft, facts, policyHtml);
        out.put("COPY_LONG", new TextAsset(longBody, with(base, Map.of("whyBuy",
                draft.whyBuy() == null ? List.of() : draft.whyBuy(),
                "faq", draft.faq() == null ? List.of() : draft.faq()))));
        String seoJson = objectMapper.writeValueAsString(orderedJson(
                "title", seoTitle == null ? "" : seoTitle,
                "description", seoDescription == null ? "" : seoDescription));
        out.put("COPY_SEO", new TextAsset(seoJson, with(base, Map.of("seoTitle",
                seoTitle, "seoDescription", seoDescription))));
        out.put("COPY_GSHOP", new TextAsset(gshopTitle, with(base, Map.of("gshopTitle",
                gshopTitle))));
        out.put("COPY_WA", new TextAsset(waMessage, with(base, Map.of("waMessage",
                waMessage))));
        return out;
    }

    /** COPY_LONG only, from the stored content (policy change re-render). */
    public TextAsset rerenderLong(long tenantId, Map<String, Object> contentJson,
            FactsJson facts, String productName, @Nullable String policyHtml,
            @Nullable Integer policyVersion) {
        CopyDraft draft = copyFromContent(contentJson);
        Map<String, Object> base = new LinkedHashMap<>(contentJson);
        base.put("policyVersion", policyVersion);
        String body = renderLong(tenantId, draft, facts, policyHtml);
        return new TextAsset(body, base);
    }

    private String renderShort(long tenantId, CopyDraft draft) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("bullets", draft.shortBullets() == null ? List.of() : draft.shortBullets());
        return render(tenantId, "COPY_SHORT", model);
    }

    private String renderLong(long tenantId, CopyDraft draft, FactsJson facts,
            @Nullable String policyHtml) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("whyBuy", draft.whyBuy() == null ? List.of() : draft.whyBuy());
        model.put("rows", specRows(facts));
        List<Map<String, Object>> faq = new ArrayList<>();
        if (draft.faq() != null) {
            for (CopyDraft.Faq entry : draft.faq()) {
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("q", entry.q());
                pair.put("a", entry.a());
                faq.add(pair);
            }
        }
        model.put("hasFaq", !faq.isEmpty());
        model.put("faq", faq);
        model.put("policyOpen", policyHtml != null);
        String body = render(tenantId, "COPY_LONG", model);
        return policyHtml != null ? body.replace(POLICY_SLOT, policyHtml) : body;
    }

    /** Same rows as the SPEC image (specModel): null/empty rows omitted. */
    static List<Map<String, String>> specRows(FactsJson facts) {
        List<Map<String, String>> rows = new ArrayList<>();
        addRow(rows, "Model", facts.model());
        addRow(rows, "Category", facts.category());
        addRow(rows, "Capacity", facts.capacity());
        addRow(rows, "Power", facts.powerW() == null ? null : facts.powerW() + " W");
        addRow(rows, "Voltage", facts.voltage());
        addRow(rows, "Material", facts.material());
        addRow(rows, "Colour", facts.colour());
        addRow(rows, "Warranty", facts.warranty());
        addRow(rows, "In the Box", join(facts.inBox()));
        addRow(rows, "Features", join(facts.features()));
        return rows;
    }

    private static void addRow(List<Map<String, String>> rows, String label,
            @Nullable String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        rows.add(Map.of("label", label, "value", value));
    }

    private static @Nullable String join(@Nullable List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(", ", values);
    }

    private String render(long tenantId, String code, Map<String, Object> model) {
        String body = templates.latestApproved(tenantId, code)
                .map(t -> t.getBody())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND",
                        "No approved template for " + code));
        return Mustache.compiler().escapeHTML(true).compile(body).execute(model);
    }

    private static Map<String, Object> orderedJson(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return out;
    }

    private static Map<String, Object> baseContent(CopyDraft draft, FactsJson facts,
            int factVersion, @Nullable Integer policyVersion) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("copy", Map.of("title", draft.title(),
                "shortBullets", draft.shortBullets(),
                "whyBuy", draft.whyBuy(),
                "faq", draft.faq(),
                "seoTitle", draft.seoTitle(),
                "seoDescription", draft.seoDescription(),
                "gshopTitle", draft.gshopTitle(),
                "waMessage", draft.waMessage()));
        base.put("factVersion", factVersion);
        base.put("policyVersion", policyVersion);
        return base;
    }

    private static Map<String, Object> with(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(extra);
        return merged;
    }

    private static CopyDraft copyFromContent(Map<String, Object> contentJson) {
        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) contentJson.get("copy");
        return new CopyDraft((String) copy.get("title"), list(copy.get("shortBullets")),
                list(copy.get("whyBuy")), faqs(copy.get("faq")),
                (String) copy.get("seoTitle"), (String) copy.get("seoDescription"),
                (String) copy.get("gshopTitle"), (String) copy.get("waMessage"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object value) {
        return value == null ? List.of() : (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<CopyDraft.Faq> faqs(Object value) {
        if (value == null) {
            return List.of();
        }
        List<CopyDraft.Faq> out = new ArrayList<>();
        for (Map<String, Object> entry : (List<Map<String, Object>>) value) {
            out.add(new CopyDraft.Faq((String) entry.get("q"), (String) entry.get("a")));
        }
        return out;
    }
}