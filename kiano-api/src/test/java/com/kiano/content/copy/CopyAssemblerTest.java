package com.kiano.content.copy;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.copy.CopyAssembler.TextAsset;
import com.kiano.content.facts.FactsJson;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * CopyAssembler: COPY_LONG order (why-buy, spec table, FAQ, policy), null
 * row omission, HTML escaping everywhere (Review Focus 4), bullet list,
 * policy placeholder, and SEO JSON.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CopyAssemblerTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CopyAssembler assembler;

    private long tenantId;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from asset");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
    }

    private static CopyDraft sampleDraft() {
        return new CopyDraft("Morgan MG-KTL17 Electric Kettle 1.7L - Boils fast",
                List.of("1.7 litre jar", "350W"), List.of("Boils water fast"),
                List.of(new CopyDraft.Faq("Does it auto shut off?", "Yes, when the water boils.")),
                "Morgan 1.7L Electric Kettle", "Morgan 1.7L Electric Kettle with auto shut-off",
                "Morgan 1.7L Electric Kettle 220-240V", "Hi! {{price}} for this Morgan kettle.");
    }

    private static FactsJson facts() {
        return new FactsJson("MG-KTL17", "Electric Kettles", "1.7 L", 350, "220-240V",
                "Stainless steel", "Silver", "1 year", List.of("Kettle", "Base"),
                List.of("Auto shut-off"), List.of("Boils quickly"), List.of());
    }

    @Test
    void long_containsWhyBuySpecTableFaqAndPolicy_inOrder() {
        Map<String, TextAsset> specs = assembler.assemble(tenantId, sampleDraft(), facts(),
                "Morgan 1.7L Kettle", "<section class=\"kiano-policy\"><p>Delivery</p></section>",
                2, 1);
        String longBody = specs.get("COPY_LONG").textBody();
        assertThat(longBody.indexOf("<h2>Why you'll love it</h2>"))
                .isLessThan(longBody.indexOf("<h2>Specifications</h2>"));
        assertThat(longBody.indexOf("<h2>Specifications</h2>"))
                .isLessThan(longBody.indexOf("Questions and answers"));
        assertThat(longBody).contains("kiano-policy");
        assertThat(longBody).doesNotContain("POLICY_PENDING");
    }

    @Test
    void long_specTableFromFacts_omitsNullRows() {
        FactsJson partial = new FactsJson("MG-KTL17", null, null, null, null, null, null, null,
                null, null, null, null);
        Map<String, TextAsset> specs = assembler.assemble(tenantId, sampleDraft(), partial,
                "Morgan 1.7L Kettle", null, null, 1);
        String longBody = specs.get("COPY_LONG").textBody();
        assertThat(longBody).contains("<th>Model</th>");
        assertThat(longBody).doesNotContain("<th>Capacity</th>");
        assertThat(longBody).doesNotContain("<th>Power</th>");
        assertThat(longBody).doesNotContain("null");
    }

    @Test
    void llmHtml_isEscapedOnlyInHtmlSpecs() {
        CopyDraft evil = new CopyDraft("Morgan <b>Kettle</b> & Co",
                List.of("<script>alert(1)</script> bullet"),
                List.of("<script>alert(1)</script> paragraph"),
                List.of(new CopyDraft.Faq("<b>&</b>", "<img src=x onerror=alert(1)>")),
                "SEO <i>title</i>", "SEO & description",
                "GShop <em>title</em>", "Hi {{price}} & <b>bold</b>");
        Map<String, TextAsset> specs = assembler.assemble(tenantId, evil, facts(),
                "Morgan 1.7L Kettle", null, null, 1);

        // Plain-text specs (Woo name / WA) carry the raw LLM text; HTML specs
        // render through Mustache {{ }} which escapes.
        assertThat(specs.get("COPY_TITLE").textBody())
                .isEqualTo("Morgan <b>Kettle</b> & Co");
        assertThat(specs.get("COPY_WA").textBody())
                .isEqualTo("Hi {{price}} & <b>bold</b>");
        assertThat(specs.get("COPY_LONG").textBody())
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain("<script");
        assertThat(specs.get("COPY_LONG").textBody())
                .contains("&lt;b&gt;&amp;&lt;/b&gt;")
                .doesNotContain("<img");
        assertThat(specs.get("COPY_SHORT").textBody())
                .contains("&lt;script&gt;")
                .doesNotContain("<script");
    }

    @Test
    void short_rendersUlWithEscapedBullets() {
        Map<String, TextAsset> specs = assembler.assemble(tenantId, sampleDraft(), facts(),
                "Morgan 1.7L Kettle", null, null, 1);
        String shortBody = specs.get("COPY_SHORT").textBody();
        assertThat(shortBody).startsWith("<ul");
        assertThat(shortBody).contains("<li>1.7 litre jar</li>");
        assertThat(shortBody).contains("</ul>");
    }

    @Test
    void policyMissing_placeholderAndNullPolicyVersion() {
        Map<String, TextAsset> specs = assembler.assemble(tenantId, sampleDraft(), facts(),
                "Morgan 1.7L Kettle", null, null, 1);
        assertThat(specs.get("COPY_LONG").textBody()).contains("<!-- POLICY_PENDING -->");
        assertThat(specs.get("COPY_LONG").contentJson().get("policyVersion")).isNull();
        assertThat(specs.get("COPY_LONG").contentJson().get("factVersion")).isEqualTo(1);
    }

    @Test
    void seo_isJsonWithTitleAndDescription() {
        Map<String, TextAsset> specs = assembler.assemble(tenantId, sampleDraft(), facts(),
                "Morgan 1.7L Kettle", null, null, 1);
        assertThat(specs.get("COPY_SEO").textBody())
                .isEqualTo("{\"title\":\"Morgan 1.7L Electric Kettle\","
                        + "\"description\":\"Morgan 1.7L Electric Kettle with auto shut-off\"}");
    }
}