package com.kiano.commerce.woo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiano.commerce.CommerceCategory;
import com.kiano.commerce.CommerceException;
import com.kiano.commerce.CommerceProduct;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.BasicCredentials;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

/**
 * WireMock-backed unit tests for {@link WooCommerceAdapter}: pagination,
 * field mapping, retries and error translation. No Spring context needed —
 * the adapter is constructed directly with test retry settings.
 */
class WooCommerceAdapterTest {

    private static final String PRODUCTS_PATH = "/wp-json/wc/v3/products";

    private WireMockServer woo;

    @BeforeEach
    void startWireMock() {
        woo = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        woo.start();
    }

    @AfterEach
    void stopWireMock() {
        woo.stop();
    }

    @Test
    void listProducts_followsTotalPagesHeader() throws Exception {
        stubProductsPage("1", fixture("products-page1.json"), 2);
        stubProductsPage("2", fixture("products-page2.json"), 2);

        List<CommerceProduct> products = adapter().listProducts();

        assertThat(products).extracting(CommerceProduct::externalId)
                .containsExactly(101L, 102L, 103L);
        woo.verify(2, getRequestedFor(urlPathEqualTo(PRODUCTS_PATH)));
    }

    @Test
    void listProducts_sendsBasicAuthStatusAnyPerPage100() {
        stubProductsPage("1", "[]", 1);

        adapter().listProducts();

        woo.verify(getRequestedFor(urlPathEqualTo(PRODUCTS_PATH))
                .withQueryParam("status", equalTo("any"))
                .withQueryParam("per_page", equalTo("100"))
                .withQueryParam("page", equalTo("1"))
                .withBasicAuth(new BasicCredentials("ck_user", "ck_pass")));
    }

    @Test
    void mapsPrices_emptyStringToNull_andScale2() throws Exception {
        stubProductsPage("1", fixture("products-page1.json"), 1);

        List<CommerceProduct> products = adapter().listProducts();

        assertThat(products.get(0).regularPrice()).isEqualTo(new BigDecimal("299.00"));
        assertThat(products.get(0).salePrice()).isNull();
        assertThat(products.get(0).price()).isEqualTo(new BigDecimal("299.00"));
        assertThat(products.get(1).regularPrice()).isEqualTo(new BigDecimal("59.50"));
        assertThat(products.get(1).salePrice()).isEqualTo(new BigDecimal("49.00"));
    }

    @Test
    void mapsSaleDates_utc_andEmptyToNull() throws Exception {
        stubProductsPage("1", fixture("products-page1.json"), 1);

        List<CommerceProduct> products = adapter().listProducts();

        // the lamp has date_on_sale_from_gmt / date_on_sale_to_gmt
        CommerceProduct lamp = products.get(1);
        assertThat(lamp.saleFromAt()).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
        assertThat(lamp.saleToAt()).isEqualTo(Instant.parse("2026-10-20T23:59:59Z"));
        // the fan has no sale dates -> null
        CommerceProduct fan = products.get(0);
        assertThat(fan.saleFromAt()).isNull();
        assertThat(fan.saleToAt()).isNull();
    }

    @Test
    void mapsImagesBrandParentAndModifiedUtc() throws Exception {
        stubProductsPage("1", fixture("products-page1.json"), 1);

        List<CommerceProduct> products = adapter().listProducts();
        CommerceProduct morgan = products.get(0);
        CommerceProduct lamp = products.get(1);

        assertThat(morgan.brand()).isEqualTo("Morgan");
        assertThat(morgan.imageUrl())
                .isEqualTo("https://shop.example.com/wp-content/uploads/2026/09/morgan-fan.jpg");
        assertThat(morgan.parentExternalId()).isNull();
        assertThat(morgan.modifiedAt()).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
        assertThat(morgan.categoryExternalIds()).containsExactly(11L, 12L);
        assertThat(morgan.sku()).isEqualTo("MG-FAN16");
        assertThat(morgan.type()).isEqualTo("variable");
        assertThat(morgan.stockQty()).isEqualTo(42);
        assertThat(lamp.imageUrl()).isNull();
        assertThat(lamp.brand()).isNull();
        assertThat(lamp.stockQty()).isNull();
        assertThat(lamp.stockStatus()).isEqualTo("onbackorder");
    }

    @Test
    void listVariations_setsTypeParentAndFallbackName() throws Exception {
        woo.stubFor(get(urlPathEqualTo("/wp-json/wc/v3/products/101/variations"))
                .withQueryParam("per_page", equalTo("100"))
                .withQueryParam("page", equalTo("1"))
                .willReturn(okJson(fixture("variations-101.json"))
                        .withHeader("X-WP-TotalPages", "1")));
        CommerceProduct parent = new CommerceProduct(101, null, "variable", "MG-FAN16", "Morgan",
                "Morgan Fan", "morgan-fan", null, null, null, null, null, "publish", null, null,
                null, List.of(), null, null);

        List<CommerceProduct> variations = adapter().listVariations(parent);

        assertThat(variations).hasSize(2);
        CommerceProduct black = variations.get(0);
        assertThat(black.type()).isEqualTo("variation");
        assertThat(black.parentExternalId()).isEqualTo(101L);
        assertThat(black.name()).isEqualTo("Morgan Fan - Black");
        assertThat(black.sku()).isEqualTo("MG-FAN16-BLK");
        assertThat(black.imageUrl())
                .isEqualTo("https://shop.example.com/wp-content/uploads/2026/09/morgan-fan-black.jpg");
        assertThat(variations.get(1).name()).isEqualTo("Morgan Fan - White, M");
    }

    @Test
    void listCategories_mapsRows_andZeroParentToNull() throws Exception {
        woo.stubFor(get(urlPathEqualTo("/wp-json/wc/v3/products/categories"))
                .withQueryParam("per_page", equalTo("100"))
                .withQueryParam("page", equalTo("1"))
                .willReturn(okJson(fixture("categories.json"))
                        .withHeader("X-WP-TotalPages", "1")));

        List<CommerceCategory> categories = adapter().listCategories();

        assertThat(categories).containsExactly(
                new CommerceCategory(11, null, "Fans", "fans"),
                new CommerceCategory(12, 11L, "Ceiling Fans", "ceiling-fans"),
                new CommerceCategory(13, null, "Accessories", "accessories"));
    }

    @Test
    void baseUrlTrailingSlash_isNormalised() {
        woo.stubFor(get(urlPathEqualTo(PRODUCTS_PATH))
                .withQueryParam("per_page", equalTo("1"))
                .willReturn(okJson("[]")));

        new WooCommerceAdapter(new WooCredentials(woo.baseUrl() + "/", "ck_user", "ck_pass"),
                testProperties()).ping();

        woo.verify(getRequestedFor(urlPathEqualTo(PRODUCTS_PATH))
                .withQueryParam("per_page", equalTo("1")));
    }

    @Test
    void retriesOn429_thenSucceeds() {
        woo.stubFor(get(urlPathEqualTo(PRODUCTS_PATH))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("recovered")
                .willReturn(aResponse().withStatus(429)));
        woo.stubFor(get(urlPathEqualTo(PRODUCTS_PATH))
                .inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson("[]").withHeader("X-WP-TotalPages", "1")));

        List<CommerceProduct> products = adapter().listProducts();

        assertThat(products).isEmpty();
        woo.verify(2, getRequestedFor(urlPathEqualTo(PRODUCTS_PATH)));
    }

    @Test
    void persistent500_throwsRetryable() {
        woo.stubFor(get(urlPathEqualTo(PRODUCTS_PATH))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> adapter().listProducts())
                .isInstanceOfSatisfying(CommerceException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("WOO_UNAVAILABLE");
                    assertThat(ex.isRetryable()).isTrue();
                });
        woo.verify(3, getRequestedFor(urlPathEqualTo(PRODUCTS_PATH)));
    }

    @Test
    void on401_throwsAuthFailedWithHint() {
        woo.stubFor(get(urlPathEqualTo(PRODUCTS_PATH))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"woocommerce_rest_cannot_view\","
                                + "\"message\":\"Sorry, you cannot view this resource.\","
                                + "\"data\":{\"status\":401}}")));

        assertThatThrownBy(() -> adapter().listProducts())
                .isInstanceOfSatisfying(CommerceException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("WOO_AUTH_FAILED");
                    assertThat(ex.isRetryable()).isFalse();
                    assertThat(ex.getMessage()).contains("WP_ENVIRONMENT_TYPE=local");
                });
        woo.verify(1, getRequestedFor(urlPathEqualTo(PRODUCTS_PATH)));
    }

    @Test
    void htmlResponse_throwsBlockedWithWafHint() {
        woo.stubFor(get(urlPathEqualTo(PRODUCTS_PATH))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Content-Type", "text/html; charset=utf-8")
                        .withBody("<html><body>Attention Required! | Cloudflare</body></html>")));

        assertThatThrownBy(() -> adapter().listProducts())
                .isInstanceOfSatisfying(CommerceException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("WOO_BLOCKED");
                    assertThat(ex.isRetryable()).isFalse();
                    assertThat(ex.getMessage()).contains("WAF");
                });
        woo.verify(1, getRequestedFor(urlPathEqualTo(PRODUCTS_PATH)));
    }

    private WooCommerceAdapter adapter() {
        return new WooCommerceAdapter(
                new WooCredentials(woo.baseUrl(), "ck_user", "ck_pass"), testProperties());
    }

    private static WooProperties testProperties() {
        WooProperties properties = new WooProperties();
        properties.setMaxAttempts(3);
        properties.setBackoff(Duration.ofMillis(10));
        return properties;
    }

    private void stubProductsPage(String page, String body, int totalPages) {
        woo.stubFor(get(urlPathEqualTo(PRODUCTS_PATH))
                .withQueryParam("status", equalTo("any"))
                .withQueryParam("per_page", equalTo("100"))
                .withQueryParam("page", equalTo(page))
                .willReturn(okJson(body).withHeader("X-WP-TotalPages", String.valueOf(totalPages))));
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = WooCommerceAdapterTest.class.getResourceAsStream("/woo/" + name)) {
            assertThat(in).as("fixture %s", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
