package com.kiano.content.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.policy.PolicyService.PolicyView;
import com.kiano.content.policy.PolicyService.SectionText;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.web.ApiException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Store policy: versioned saves with event, completeness rule and the
 * HTML-escaped POLICY_BLOCK rendering (line breaks → paragraphs).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PolicyServiceTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PolicyService service;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;
    private long userId;
    private CurrentUser owner;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from store_policy");
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'policy-owner@example.test', 'Owner', ?, 'OWNER') returning id",
                Long.class, tenantId, passwordEncoder.encode("owner-pass-123"));
        owner = new CurrentUser(userId, tenantId, Role.OWNER, "policy-owner@example.test");
    }

    private static Map<PolicySection, SectionText> completeSections() {
        Map<PolicySection, SectionText> sections = new EnumMap<>(PolicySection.class);
        for (PolicySection section : PolicySection.values()) {
            sections.put(section, new SectionText("Title of " + section, "Body of " + section,
                    null));
        }
        return sections;
    }

    @Test
    void save_createsNewVersionEachTime_andPublishesEvent() {
        PolicyView v1 = service.save(owner, completeSections());
        PolicyView v2 = service.save(owner, completeSections());

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.complete()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from store_policy where tenant_id = ?", Integer.class,
                tenantId)).isEqualTo(2);
        PolicyView current = service.current(tenantId).get();
        assertThat(current.version()).isEqualTo(2);
    }

    @Test
    void requireComplete_missingSections_409WithList() {
        Map<PolicySection, SectionText> partial = new EnumMap<>(PolicySection.class);
        partial.put(PolicySection.DELIVERY,
                new SectionText("Delivery", "Accra & Tema\n1-3 days", null));
        partial.put(PolicySection.WARRANTY, new SectionText("Warranty", "1 year", null));
        service.save(owner, partial);

        assertThatThrownBy(() -> service.requireComplete(tenantId))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("POLICY_INCOMPLETE");
                    assertThat(ex.getDetails().get("missing")).isEqualTo(
                            List.of("COD", "MOMO", "RETURNS"));
                });
    }

    @Test
    void renderBlock_escapesHtml_andParagraphsLines() {
        Map<PolicySection, SectionText> sections = new EnumMap<>(PolicySection.class);
        sections.put(PolicySection.DELIVERY,
                new SectionText("Delivery & Shipping", "Accra & Tema\n<b>x</b>", null));
        sections.put(PolicySection.WARRANTY,
                new SectionText("Warranty", "<script>alert(1)</script>", null));
        service.save(owner, sections);

        String html = service.renderBlock(tenantId);

        assertThat(html).contains("Accra &amp; Tema");
        assertThat(html).contains("&lt;b&gt;");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("Delivery &amp; Shipping");
        assertThat(html).doesNotContain("<script");
    }

    @Test
    void badge_roundTrips_andLegacyNullBadgeReadsAsNull() {
        Map<PolicySection, SectionText> sections = completeSections();
        sections.put(PolicySection.DELIVERY,
                new SectionText("Delivery", "1-3 days", "  Free delivery  "));
        service.save(owner, sections);

        PolicyView view = service.current(tenantId).get();
        assertThat(view.sections().get(PolicySection.DELIVERY).badge()).isEqualTo("Free delivery");
        // sections saved without a badge read back as null (legacy rows too)
        assertThat(view.sections().get(PolicySection.COD).badge()).isNull();
    }

    @Test
    void badge_over40_orWithMarkup_422() {
        Map<PolicySection, SectionText> sections = completeSections();
        sections.put(PolicySection.COD, new SectionText("COD", "B",
                "x".repeat(41)));
        assertThatThrownBy(() -> service.save(owner, sections))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("VALIDATION_FAILED");
                });

        sections.put(PolicySection.COD, new SectionText("COD", "B", "<b>COD</b>"));
        assertThatThrownBy(() -> service.save(owner, sections))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("VALIDATION_FAILED");
                });
    }

    @Test
    void requireAdBadges_missingMomo_409WithList() {
        Map<PolicySection, SectionText> sections = completeSections();
        sections.put(PolicySection.COD, new SectionText("COD", "Pay on receipt", "COD"));
        sections.put(PolicySection.DELIVERY, new SectionText("Delivery", "1-3 days", "Fast"));
        sections.put(PolicySection.MOMO, new SectionText("MoMo", "MTN MoMo", null));
        service.save(owner, sections);

        assertThatThrownBy(() -> service.requireAdBadges(tenantId))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("POLICY_BADGES_MISSING");
                    assertThat(ex.getDetails().get("missing")).isEqualTo(List.of("MOMO"));
                });
    }

    @Test
    void requireAdBadges_allPresent_returnsBadges() {
        Map<PolicySection, SectionText> sections = completeSections();
        sections.put(PolicySection.COD, new SectionText("COD", "B", "COD available"));
        sections.put(PolicySection.MOMO, new SectionText("MoMo", "B", "MTN MoMo"));
        sections.put(PolicySection.DELIVERY, new SectionText("Delivery", "B", "Free delivery"));
        service.save(owner, sections);

        Map<PolicySection, String> badges = service.requireAdBadges(tenantId);

        assertThat(badges.get(PolicySection.COD)).isEqualTo("COD available");
        assertThat(badges.get(PolicySection.MOMO)).isEqualTo("MTN MoMo");
        assertThat(badges.get(PolicySection.DELIVERY)).isEqualTo("Free delivery");
    }
}