package com.kiano.content.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.TestcontainersConfiguration;
import com.kiano.content.facts.FactSheetService;
import com.kiano.content.facts.FactsJson;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.auth.Role;
import com.kiano.platform.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Text asset review (Task 8): listing order with body/charCount, manual edit
 * (new IN_REVIEW version + re-precheck), jsoup HTML whitelist, edit guard on
 * non-editable statuses, and per-spec regeneration enqueue.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class TextReviewTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private FactSheetService factSheetService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long tenantId;
    private long storeId;
    private long userId;
    private long productId;
    private int factVersion;
    private CurrentUser user;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("delete from platform_task where tenant_id = "
                + "(select id from tenant where slug = 'kianosmart')");
        jdbcTemplate.update("delete from asset_review");
        jdbcTemplate.update("delete from asset");
        jdbcTemplate.update("delete from product_fact_sheet");
        jdbcTemplate.update("delete from llm_call");
        jdbcTemplate.update("delete from product_category");
        jdbcTemplate.update("delete from product");
        jdbcTemplate.update("delete from category");
        jdbcTemplate.update("delete from store where platform = 'WOOCOMMERCE'");
        jdbcTemplate.update("delete from app_user");
        tenantId = jdbcTemplate.queryForObject("select id from tenant where slug = 'kianosmart'",
                Long.class);
        storeId = jdbcTemplate.queryForObject(
                "insert into store (tenant_id, platform, base_url) "
                        + "values (?, 'WOOCOMMERCE', 'http://woo.test') returning id",
                Long.class, tenantId);
        productId = jdbcTemplate.queryForObject(
                "insert into product (tenant_id, store_id, external_id, type, sku, name, status, synced_at) "
                        + "values (?, ?, -7000, 'simple', 'MG-KTL17', 'Kettle 1.7L', 'publish', now()) "
                        + "returning id",
                Long.class, tenantId, storeId);
        userId = jdbcTemplate.queryForObject(
                "insert into app_user (tenant_id, email, name, password_hash, role) "
                        + "values (?, 'textreview-op@example.test', 'Op', ?, 'OPERATOR') returning id",
                Long.class, tenantId, passwordEncoder.encode("op-pass-123"));
        user = new CurrentUser(userId, tenantId, Role.OPERATOR, "textreview-op@example.test");
        FactsJson facts = new FactsJson("MG-KTL17", "Electric Kettles", "1.7 L", 350,
                "220-240V", "Stainless steel", "Silver", "1 year", List.of("Kettle", "Base"),
                List.of("Auto shut-off"), List.of(), List.of());
        factSheetService.saveDraft(user, productId, facts,
                Map.of("model", com.kiano.content.facts.FieldSource.P5));
        factVersion = factSheetService.lock(user, productId, 1,
                Set.of("model", "capacity", "powerW", "voltage", "warranty", "inBox")).version();
    }

    private long textAsset(String spec, String status, String body, String contentJson,
            int version) {
        return jdbcTemplate.queryForObject(
                "insert into asset (tenant_id, product_id, spec_code, variant, version, kind, "
                        + "text_body, content_json, status, precheck_json, provenance_json, "
                        + "fact_version, created_at) values (?, ?, ?, 'default', ?, 'TEXT', ?, "
                        + "?, ?, '{}', '{}', ?, now()) returning id",
                Long.class, tenantId, productId, spec, version, body, contentJson, status,
                factVersion);
    }

    @Test
    void list_textAssets_haveBodyAndCharCount_afterImages() {
        long longId = textAsset("COPY_LONG", "IN_REVIEW",
                "<p>Hello <b>world</b></p>", "{}", 1);
        long titleId = textAsset("COPY_TITLE", "IN_REVIEW", "Morgan Kettle", null, 1);

        List<ReviewService.ReviewItem> items = reviewService.list(user, productId, null, null);

        ReviewService.ReviewItem longItem = items.stream()
                .filter(i -> i.assetId() == longId).findFirst().get();
        assertThat(longItem.kind()).isEqualTo("TEXT");
        assertThat(longItem.textBody()).isEqualTo("<p>Hello <b>world</b></p>");
        assertThat(longItem.charCount()).isEqualTo(11); // "Hello world"
        assertThat(longItem.factVersion()).isEqualTo(factVersion);
        assertThat(longItem.imageUrl()).isNull();
        ReviewService.ReviewItem titleItem = items.stream()
                .filter(i -> i.assetId() == titleId).findFirst().get();
        assertThat(titleItem.charCount()).isEqualTo(13);
        // Text specs sort after images, and TITLE(10) before LONG(12).
        assertThat(items.indexOf(titleItem)).isEqualTo(items.indexOf(longItem) - 1);
    }

    @Test
    void edit_createsNewVersionInReview_rerunsPrecheck() {
        long id = textAsset("COPY_LONG", "IN_REVIEW",
                "<p>1500 ml kettle</p>", "{\"copy\":{}}", 1);

        ReviewService.ReviewItem edited = reviewService.editText(user, id,
                "<p>1500 ml kettle with 500W element</p>");

        assertThat(edited.assetId()).isNotEqualTo(id);
        assertThat(edited.version()).isEqualTo(2);
        assertThat(edited.status()).isEqualTo("IN_REVIEW");
        assertThat(edited.textBody()).isEqualTo("<p>1500 ml kettle with 500W element</p>");
        assertThat(edited.flags()).contains(PrecheckFlag.FACT_MISMATCH);
        // old version archived by createText
        assertThat(jdbcTemplate.queryForObject(
                "select status from asset where id = ?", String.class, id))
                .isEqualTo("ARCHIVED");
    }

    @Test
    void edit_disallowedHtml_422() {
        long id = textAsset("COPY_LONG", "IN_REVIEW", "<p>ok</p>", "{}", 1);

        assertThatThrownBy(() -> reviewService.editText(user, id, "<script>alert(1)</script>"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getCode()).isEqualTo("TEXT_HTML_NOT_ALLOWED");
                });
        assertThatThrownBy(() -> reviewService.editText(user, id, "<img src=x onerror=1>"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TEXT_HTML_NOT_ALLOWED"));
    }

    @Test
    void edit_plainTextSpec_withAnyMarkup_422() {
        // COPY_TITLE becomes the Woo product name, which themes print unescaped.
        long title = textAsset("COPY_TITLE", "IN_REVIEW", "Morgan Kettle", null, 1);
        long seo = textAsset("COPY_SEO", "IN_REVIEW",
                "{\"title\":\"t\",\"description\":\"d\"}", null, 1);

        assertThatThrownBy(() -> reviewService.editText(user, title,
                "Morgan <img src=x onerror=alert(1)> Kettle"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TEXT_HTML_NOT_ALLOWED"));
        assertThatThrownBy(() -> reviewService.editText(user, seo,
                "{\"title\":\"<b>t</b>\",\"description\":\"d\"}"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("TEXT_HTML_NOT_ALLOWED"));
        // Plain characters such as & and a lone < are fine.
        assertThat(reviewService.editText(user, title, "Morgan Kettle & Base < 2kg").textBody())
                .isEqualTo("Morgan Kettle & Base < 2kg");
    }

    @Test
    void edit_onApproved_409() {
        long id = textAsset("COPY_TITLE", "APPROVED", "Morgan Kettle", null, 1);

        assertThatThrownBy(() -> reviewService.editText(user, id, "New title"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("ASSET_NOT_EDITABLE");
                });
    }

    @Test
    void regenerate_text_enqueuesOnlySpec() {
        // Facts were locked during seed, which auto-enqueues a full
        // COPY_GENERATE; drop those so this test only sees the REGENERATE one.
        jdbcTemplate.update("delete from platform_task where tenant_id = ? and type = ?",
                tenantId, "COPY_GENERATE");
        long id = textAsset("COPY_SEO", "IN_REVIEW", "{\"title\":\"t\",\"description\":\"d\"}",
                "{}", 1);

        reviewService.decide(user, id, ReviewService.Decision.REGENERATE, List.of(), null);

        assertThat(jdbcTemplate.queryForList(
                "select payload from platform_task where tenant_id = ? and type = 'COPY_GENERATE'",
                String.class, tenantId))
                .hasSize(1)
                .allSatisfy(payload -> assertThat(
                        tools.jackson.databind.json.JsonMapper.builder().build().readTree(payload)
                                .path("onlySpec").asText()).isEqualTo("COPY_SEO"));
        assertThat(jdbcTemplate.queryForObject("select status from asset where id = ?",
                String.class, id)).isEqualTo("ARCHIVED");
    }
}