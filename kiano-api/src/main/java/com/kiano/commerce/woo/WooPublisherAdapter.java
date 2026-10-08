package com.kiano.commerce.woo;

import com.kiano.commerce.CommerceException;
import com.kiano.commerce.CommercePublisher;
import com.kiano.commerce.ProductContentUpdate;
import com.kiano.commerce.WooMedia;
import com.kiano.commerce.WooProductSnapshot;
import com.kiano.commerce.WooProductSnapshot.RankMath;
import com.kiano.commerce.WooProductSnapshot.WooImageRef;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Write-side WooCommerce port (C3 Task 9): product lookup by SKU, media
 * upload via wp/v2/media (with alt text), product content updates via
 * wc/v3/products (name, descriptions, ordered images, Rank Math meta) and
 * media deletion. Write operations never retry 5xx inside the adapter - a
 * retried POST would duplicate media; the publish flow owns recovery.
 * Not a Spring bean: built per tenant/environment by
 * {@link CommercePublisherFactory}.
 */
public class WooPublisherAdapter implements CommercePublisher {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final RestClient wcClient;
    private final RestClient wpClient;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final String basicUser;
    private final String basicPassword;

    public WooPublisherAdapter(WooCredentials credentials, WooProperties properties) {
        String base = normalize(credentials.baseUrl());
        this.basicUser = credentials.username();
        this.basicPassword = credentials.applicationPassword();
        this.wcClient = RestClient.builder()
                .baseUrl(base + "/wp-json/wc/v3")
                .requestFactory(requestFactory())
                .build();
        this.wpClient = RestClient.builder()
                .baseUrl(base + "/wp-json/wp/v2")
                .requestFactory(requestFactory())
                .build();
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    @Override
    public Optional<WooProductSnapshot> findBySku(String sku) {
        String body = getOne(wcClient, "/products?sku={sku}", sku);
        if (body == null) {
            return Optional.empty();
        }
        JsonNode products = objectMapper.readTree(body);
        if (!products.isArray() || products.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toSnapshot(products.get(0)));
    }

    @Override
    public WooProductSnapshot get(long productId) {
        return toSnapshot(objectMapper.readTree(getOne(wcClient, "/products/{id}", productId)));
    }

    @Override
    public WooMedia uploadMedia(String fileName, byte[] bytes, String contentType,
            String altText) {
        JsonNode created = objectMapper.readTree(postRaw(wpClient, "/media", fileName, bytes,
                contentType));
        long id = created.path("id").asLong();
        // Set the alt text in a second call (wp/v2/media/{id}).
        String altBody = objectMapper.writeValueAsString(Map.of("alt_text", altText));
        JsonNode updated = objectMapper.readTree(postJson(wpClient, "/media/" + id, altBody));
        return new WooMedia(id, updated.path("source_url").asText(""));
    }

    @Override
    public WooProductSnapshot updateContent(long productId, ProductContentUpdate update) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("name", update.name());
        payload.put("description", update.description());
        payload.put("short_description", update.shortDescription());
        List<Map<String, Object>> images = new ArrayList<>();
        for (Long imageId : update.imageIdsInOrder()) {
            images.add(ordered("id", imageId));
        }
        payload.put("images", images);
        if (update.seoTitle() != null || update.seoDescription() != null) {
            List<Map<String, Object>> meta = new ArrayList<>();
            if (update.seoTitle() != null) {
                meta.add(ordered("key", "rank_math_title", "value", update.seoTitle()));
            }
            if (update.seoDescription() != null) {
                meta.add(ordered("key", "rank_math_description", "value",
                        update.seoDescription()));
            }
            payload.put("meta_data", meta);
        }
        JsonNode updated = objectMapper.readTree(putJson(wcClient, "/products/" + productId,
                objectMapper.writeValueAsString(payload)));
        return toSnapshot(updated);
    }

    @Override
    public void deleteMedia(long mediaId) {
        deleteRaw(wpClient, "/media/" + mediaId + "?force=true");
    }

    private WooProductSnapshot toSnapshot(JsonNode node) {
        List<WooImageRef> images = new ArrayList<>();
        for (JsonNode image : node.path("images")) {
            images.add(new WooImageRef(image.path("id").asLong(), image.path("src").asText(""),
                    image.path("position").asInt(0), image.path("alt").asText("")));
        }
        String title = null;
        String description = null;
        for (JsonNode meta : node.path("meta_data")) {
            if ("rank_math_title".equals(meta.path("key").asText())) {
                title = meta.path("value").asText(null);
            } else if ("rank_math_description".equals(meta.path("key").asText())) {
                description = meta.path("value").asText(null);
            }
        }
        return new WooProductSnapshot(node.path("id").asLong(), node.path("sku").asText(""),
                node.path("name").asText(), textOrNull(node, "description"),
                textOrNull(node, "short_description"), images, new RankMath(title, description),
                modifiedAt(node));
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.path(field).asText(null);
    }

    private static Instant modifiedAt(JsonNode node) {
        String raw = node.path("date_modified_gmt").asText(null);
        return raw == null || raw.isBlank() ? null
                : LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC);
    }

    // ---- transport helpers (never retry writes inside the adapter) ----

    private String getOne(RestClient client, String uri, Object... vars) {
        try {
            return client.get().uri(uri, vars)
                    .headers(headers -> headers.setBasicAuth(basicUser, basicPassword))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        String body = new String(response.getBody().readAllBytes(),
                                StandardCharsets.UTF_8);
                        classify(status, body);
                        return status == 404 ? null : body;
                    });
        } catch (ResourceAccessException | UncheckedIOException ex) {
            throw unavailable(ex.getMessage());
        }
    }

    private String postRaw(RestClient client, String uri, String fileName, byte[] bytes,
            String contentType) {
        return executeWrite(client.post()
                .uri(uri)
                .headers(headers -> {
                    headers.setBasicAuth(basicUser, basicPassword);
                    headers.setContentType(MediaType.parseMediaType(contentType));
                    headers.set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
                })
                .body(bytes));
    }

    private String postJson(RestClient client, String uri, String json) {
        return executeWrite(client.post()
                .uri(uri)
                .headers(headers -> {
                    headers.setBasicAuth(basicUser, basicPassword);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                })
                .body(json));
    }

    private String putJson(RestClient client, String uri, String json) {
        return executeWrite(client.put()
                .uri(uri)
                .headers(headers -> {
                    headers.setBasicAuth(basicUser, basicPassword);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                })
                .body(json));
    }

    private void deleteRaw(RestClient client, String uri) {
        try {
            client.delete().uri(uri)
                    .headers(headers -> headers.setBasicAuth(basicUser, basicPassword))
                    .exchange((request, response) -> {
                        classify(response.getStatusCode().value(),
                                new String(response.getBody().readAllBytes(),
                                        StandardCharsets.UTF_8));
                        return null;
                    });
        } catch (ResourceAccessException | UncheckedIOException ex) {
            throw unavailable(ex.getMessage());
        }
    }

    /** One write exchange, never retried; maps status to CommerceException. */
    private String executeWrite(org.springframework.web.client.RestClient.RequestBodySpec spec) {
        try {
            return spec.headers(headers -> headers.setBasicAuth(basicUser, basicPassword))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        String body = new String(response.getBody().readAllBytes(),
                                StandardCharsets.UTF_8);
                        classify(status, body);
                        return body;
                    });
        } catch (ResourceAccessException | UncheckedIOException ex) {
            throw unavailable(ex.getMessage());
        }
    }

    /** 401/403 → auth, 4xx → HTTP error, 429/5xx → unavailable. */
    private static void classify(int status, String body) {
        if (status == 401 || status == 403) {
            throw new CommerceException("WOO_AUTH_FAILED",
                    "WooCommerce rejected the credentials (HTTP " + status + ").", false);
        }
        if (status >= 400 && status < 500) {
            throw new CommerceException("WOO_HTTP_ERROR",
                    "WooCommerce returned HTTP " + status + ": " + truncate(body), false);
        }
        if (status >= 500 || status == 429) {
            throw unavailable("HTTP " + status + ": " + truncate(body));
        }
    }

    private static String truncate(String body) {
        return body == null || body.length() <= 300 ? body : body.substring(0, 300);
    }

    private static CommerceException unavailable(String detail) {
        return new CommerceException("WOO_UNAVAILABLE",
                "WooCommerce write failed: " + detail, true);
    }

    /** key-first LinkedHashMap so the serialized raw JSON stays in order. */
    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private static String normalize(String baseUrl) {
        String trimmed = baseUrl;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}