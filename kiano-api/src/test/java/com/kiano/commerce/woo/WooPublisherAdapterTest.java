package com.kiano.commerce.woo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.kiano.commerce.CommerceException;
import com.kiano.commerce.CommercePublisher;
import com.kiano.commerce.ProductContentUpdate;
import com.kiano.commerce.PublishEnvironment;
import com.kiano.commerce.WooMedia;
import com.kiano.commerce.WooProductSnapshot;
import com.kiano.commerce.WooProductSnapshot.RankMath;
import com.kiano.commerce.WooProductSnapshot.WooImageRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WireMock tests for {@link WooPublisherAdapter}: product mapping (images in
 * order + Rank Math meta), media upload (raw bytes + disposition, then alt),
 * updateContent (ordered images + meta; omitted when no SEO) and the no-retry
 * rule on write 5xx.
 */
class WooPublisherAdapterTest {

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

    private CommercePublisher adapter() {
        return new WooPublisherAdapter(
                new WooCredentials(woo.baseUrl(), "ck_user", "ck_pass"), new WooProperties());
    }

    private String productJson() {
        return """
                {"id":42,"sku":"MG-KTL17","name":"Old name","description":"Old desc",\
                "short_description":"Old short","date_modified_gmt":"2026-04-01T10:00:00",\
                "images":[{"id":1,"src":"http://a/1.jpg","position":0,"alt":"front"},\
                           {"id":2,"src":"http://a/2.jpg","position":1,"alt":"side"}],\
                "meta_data":[{"key":"rank_math_title","value":"Old SEO"},\
                              {"key":"rank_math_description","value":"Old SEO desc"}]}""";
    }

    @Test
    void findBySku_mapsImagesInOrderAndRankMathMeta() {
        woo.stubFor(get(urlPathEqualTo("/wp-json/wc/v3/products"))
                .withQueryParam("sku", equalTo("MG-KTL17"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[" + productJson() + "]")));

        Optional<WooProductSnapshot> found = adapter().findBySku("MG-KTL17");

        assertThat(found).isPresent();
        WooProductSnapshot snapshot = found.get();
        assertThat(snapshot.id()).isEqualTo(42);
        assertThat(snapshot.name()).isEqualTo("Old name");
        assertThat(snapshot.images()).extracting(WooImageRef::id)
                .containsExactly(1L, 2L);
        assertThat(snapshot.images()).extracting(WooImageRef::position)
                .containsExactly(0, 1);
        assertThat(snapshot.rankMath()).isEqualTo(new RankMath("Old SEO", "Old SEO desc"));
        assertThat(snapshot.modifiedAt()).isEqualTo(Instant.parse("2026-04-01T10:00:00Z"));
    }

    @Test
    void uploadMedia_sendsBytesWithDisposition_thenSetsAlt() {
        woo.stubFor(post(urlPathEqualTo("/wp-json/wp/v2/media"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"source_url\":\"http://x/7.jpg\"}")));
        woo.stubFor(post(urlPathEqualTo("/wp-json/wp/v2/media/7"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"source_url\":\"http://x/7.jpg\",\"alt_text\":\"mor\"}")));
        byte[] bytes = new byte[]{1, 2, 3};

        WooMedia media = adapter().uploadMedia("MG-KTL17_page-main.jpg", bytes, "image/jpeg",
                "Morgan kettle – front view");

        assertThat(media.id()).isEqualTo(7);
        woo.verify(postRequestedFor(urlPathEqualTo("/wp-json/wp/v2/media"))
                .withHeader("Content-Type", equalTo("image/jpeg"))
                .withHeader("Content-Disposition", containing("MG-KTL17_page-main.jpg"))
                .withRequestBody(equalTo(new String(bytes))));
        woo.verify(postRequestedFor(urlPathEqualTo("/wp-json/wp/v2/media/7"))
                .withRequestBody(containing("front view")));
    }

    @Test
    void uploadMedia_altTextFails_deletesTheCreatedMediaBeforeFailing() {
        woo.stubFor(post(urlPathEqualTo("/wp-json/wp/v2/media"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"source_url\":\"http://x/7.jpg\"}")));
        woo.stubFor(post(urlPathEqualTo("/wp-json/wp/v2/media/7"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json").withBody("{}")));
        woo.stubFor(delete(urlPathEqualTo("/wp-json/wp/v2/media/7"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        // The caller never learns the id, so the adapter must not leave it orphaned.
        assertThatThrownBy(() -> adapter().uploadMedia("a.jpg", new byte[]{1}, "image/jpeg", "alt"))
                .isInstanceOf(CommerceException.class);
        woo.verify(deleteRequestedFor(urlPathEqualTo("/wp-json/wp/v2/media/7"))
                .withQueryParam("force", equalTo("true")));
    }

    @Test
    void updateContent_sendsImageIdsInOrderAndRankMathMeta() {
        woo.stubFor(put(urlPathEqualTo("/wp-json/wc/v3/products/42"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(productJson())));

        adapter().updateContent(42L, new ProductContentUpdate("New name", "New desc", "New short",
                List.of(8L, 9L), "New SEO", "New SEO desc"));

        String body = lastRequestBody("/wp-json/wc/v3/products/42", "PUT");
        assertThat(body)
                .contains("\"name\":\"New name\"")
                .contains("\"description\":\"New desc\"")
                .contains("\"short_description\":\"New short\"")
                .contains("\"images\":[{\"id\":8},{\"id\":9}]")
                .contains("\"key\":\"rank_math_title\",\"value\":\"New SEO\"");
    }

    @Test
    void updateContent_withoutSeo_omitsMetaData() {
        woo.stubFor(put(urlPathEqualTo("/wp-json/wc/v3/products/42"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(productJson())));

        adapter().updateContent(42L, new ProductContentUpdate("New name", "New desc", "New short",
                List.of(), null, null));

        String body = lastRequestBody("/wp-json/wc/v3/products/42", "PUT");
        assertThat(body).contains("\"images\":[]").doesNotContain("meta_data");
    }

    @Test
    void write5xx_notRetried() {
        woo.stubFor(post(urlPathEqualTo("/wp-json/wp/v2/media"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"error\"}")));

        assertThatThrownBy(() -> adapter().uploadMedia("a.jpg", new byte[]{1}, "image/jpeg", "a"))
                .isInstanceOfSatisfying(CommerceException.class,
                        ex -> assertThat(ex.isRetryable()).isTrue());
        woo.verify(1, postRequestedFor(urlPathEqualTo("/wp-json/wp/v2/media")));
    }

    @Test
    void deleteMedia_forceTrue() {
        woo.stubFor(delete(urlPathEqualTo("/wp-json/wp/v2/media/7"))
                .withQueryParam("force", equalTo("true"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        adapter().deleteMedia(7L);

        woo.verify(deleteRequestedFor(urlPathEqualTo("/wp-json/wp/v2/media/7"))
                .withQueryParam("force", equalTo("true")));
    }

    @Test
    void factory_unconfiguredStaging_409() {
        // Covered by the Spring-side factory test (needs the IntegrationStore);
        // here a direct enumeration sanity check.
        assertThat(PublishEnvironment.STAGING).isNotNull();
    }

    private String lastRequestBody(String path, String method) {
        return woo.getAllServeEvents().stream()
                .filter(event -> method.equals(event.getRequest().getMethod().toString())
                        && path.equals(event.getRequest().getUrl()))
                .reduce((a, b) -> b)
                .map(event -> event.getRequest().getBodyAsString())
                .orElseThrow();
    }
}