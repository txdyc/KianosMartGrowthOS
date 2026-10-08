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
            sections.put(section, new SectionText("Title of " + section, "Body of " + section));
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
        partial.put(PolicySection.DELIVERY, new SectionText("Delivery", "Accra & Tema\n1-3 days"));
        partial.put(PolicySection.WARRANTY, new SectionText("Warranty", "1 year"));
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
                new SectionText("Delivery & Shipping", "Accra & Tema\n<b>x</b>"));
        sections.put(PolicySection.WARRANTY, new SectionText("Warranty", "<script>alert(1)</script>"));
        service.save(owner, sections);

        String html = service.renderBlock(tenantId);

        assertThat(html).contains("Accra &amp; Tema");
        assertThat(html).contains("&lt;b&gt;");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("Delivery &amp; Shipping");
        assertThat(html).doesNotContain("<script");
    }
}