package com.kiano.commerce.woo;

import com.kiano.commerce.CommerceCategory;
import com.kiano.commerce.CommerceException;
import com.kiano.commerce.CommercePort;
import com.kiano.commerce.CommerceProduct;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only WooCommerce implementation of {@link CommercePort}. Talks to the
 * Woo REST API v3 with HTTP Basic auth, follows X-WP-TotalPages pagination
 * (per_page=100), and translates transport failures into {@link CommerceException}
 * with stable codes. Not a Spring bean: one instance per tenant is created by
 * the integration factory with the tenant's stored credentials.
 */
public class WooCommerceAdapter implements CommercePort {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    private static final int PER_PAGE = 100;
    private static final String TOTAL_PAGES_HEADER = "X-WP-TotalPages";

    private final WooProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final String basicUser;
    private final String basicPassword;

    public WooCommerceAdapter(WooCredentials credentials, WooProperties properties) {
        this.properties = properties;
        this.basicUser = credentials.username();
        this.basicPassword = credentials.applicationPassword();
        this.restClient = RestClient.builder()
                .baseUrl(normalise(credentials.baseUrl()) + "/wp-json/wc/v3")
                .requestFactory(requestFactory())
                .build();
    }

    static String normalise(String baseUrl) {
        String trimmed = baseUrl;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    @Override
    public List<CommerceCategory> listCategories() {
        List<CommerceCategory> categories = new ArrayList<>();
        for (JsonNode node : getAll("/products/categories")) {
            categories.add(toCategory(node));
        }
        return categories;
    }

    @Override
    public List<CommerceProduct> listProducts() {
        List<CommerceProduct> products = new ArrayList<>();
        for (JsonNode node : getAll("/products", "status", "any")) {
            products.add(toProduct(node, null));
        }
        return products;
    }

    @Override
    public List<CommerceProduct> listVariations(CommerceProduct parent) {
        List<CommerceProduct> variations = new ArrayList<>();
        for (JsonNode node : getAll("/products/" + parent.externalId() + "/variations")) {
            variations.add(toProduct(node, parent));
        }
        return variations;
    }

    @Override
    public void ping() {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.add("per_page", "1");
        get("/products", query);
    }

    /**
     * Fetches every page of a Woo collection, following the X-WP-TotalPages
     * header. extraQuery is a flat k1,v1,k2,v2,... array.
     */
    private List<JsonNode> getAll(String path, String... extraQuery) {
        List<JsonNode> items = new ArrayList<>();
        int page = 1;
        int totalPages = 1;
        do {
            MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
            for (int i = 0; i < extraQuery.length; i += 2) {
                query.add(extraQuery[i], extraQuery[i + 1]);
            }
            query.add("per_page", String.valueOf(PER_PAGE));
            query.add("page", String.valueOf(page));
            WooResponse response = get(path, query);
            JsonNode body = objectMapper.readTree(response.body());
            if (body.isArray()) {
                body.forEach(items::add);
            }
            totalPages = response.totalPages();
            page++;
        } while (page <= totalPages);
        return items;
    }

    /**
     * Executes one GET with retries: 429/5xx (and connection failures) are
     * retried up to maxAttempts with the configured backoff, then fail as
     * WOO_UNAVAILABLE; non-JSON responses as WOO_BLOCKED; 401/403 as
     * WOO_AUTH_FAILED.
     */
    private WooResponse get(String path, MultiValueMap<String, String> query) {
        CommerceException failure = null;
        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            if (attempt > 1) {
                sleep();
            }
            try {
                WooResponse response = executeOnce(path, query);
                int status = response.status();
                if (status == 429 || status >= 500) {
                    failure = unavailable("HTTP " + status);
                } else if (!isJson(response.headers())) {
                    throw blocked();
                } else if (status == 401 || status == 403) {
                    throw authFailed();
                } else if (status >= 400) {
                    throw new CommerceException("WOO_HTTP_ERROR",
                            "WooCommerce returned HTTP " + status, false);
                } else {
                    return response;
                }
            } catch (CommerceException ex) {
                if (!ex.isRetryable()) {
                    throw ex;
                }
                failure = ex;
            }
        }
        throw failure;
    }

    private WooResponse executeOnce(String path, MultiValueMap<String, String> query) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> {
                        UriBuilder builder = uriBuilder.path(path);
                        for (Map.Entry<String, List<String>> entry : query.entrySet()) {
                            builder.queryParam(entry.getKey(), entry.getValue().toArray());
                        }
                        return builder.build();
                    })
                    .headers(headers -> headers.setBasicAuth(basicUser, basicPassword))
                    .exchange((request, response) -> new WooResponse(
                            response.getStatusCode().value(),
                            response.getHeaders(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException | UncheckedIOException ex) {
            throw unavailable(ex.getMessage());
        }
    }

    private static boolean isJson(HttpHeaders headers) {
        MediaType contentType = headers.getContentType();
        if (contentType == null) {
            return false;
        }
        return contentType.isCompatibleWith(MediaType.APPLICATION_JSON)
                || contentType.getSubtype().endsWith("+json");
    }

    private CommerceException unavailable(String detail) {
        return new CommerceException("WOO_UNAVAILABLE",
                "WooCommerce is unavailable after " + properties.getMaxAttempts()
                        + " attempts: " + detail, true);
    }

    private static CommerceException authFailed() {
        return new CommerceException("WOO_AUTH_FAILED",
                "WooCommerce rejected the credentials. Check the username and Application Password; "
                        + "on an http:// site WordPress also needs WP_ENVIRONMENT_TYPE=local.", false);
    }

    private static CommerceException blocked() {
        return new CommerceException("WOO_BLOCKED",
                "WooCommerce returned a non-JSON response (likely a Cloudflare challenge). "
                        + "Allow the Kiano server IP for /wp-json/wc/ in the WAF.", false);
    }

    private void sleep() {
        try {
            Thread.sleep(properties.getBackoff().toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CommerceException("WOO_UNAVAILABLE",
                    "Interrupted while backing off before retrying WooCommerce", true);
        }
    }

    private CommerceCategory toCategory(JsonNode node) {
        long parent = node.path("parent").asLong(0);
        return new CommerceCategory(node.path("id").asLong(), parent == 0 ? null : parent,
                node.path("name").asText(), node.path("slug").asText());
    }

    private CommerceProduct toProduct(JsonNode node, CommerceProduct parent) {
        String rawName = node.path("name").asText("");
        String name = parent != null && rawName.isBlank()
                ? parent.name() + " - " + joinedOptions(node.path("attributes"))
                : rawName;
        Long parentExternalId = parent != null
                ? Long.valueOf(parent.externalId())
                : parentExternalId(node);
        return new CommerceProduct(
                node.path("id").asLong(),
                parentExternalId,
                parent != null ? "variation" : node.path("type").asText(),
                textOrNull(node, "sku"),
                brand(node),
                name,
                textOrNull(node, "slug"),
                price(node, "regular_price"),
                price(node, "sale_price"),
                price(node, "price"),
                stockQty(node),
                textOrNull(node, "stock_status"),
                textOrNull(node, "status"),
                textOrNull(node, "permalink"),
                imageUrl(node),
                modifiedAt(node),
                categoryExternalIds(node));
    }

    private static Long parentExternalId(JsonNode node) {
        long parentId = node.path("parent_id").asLong(0);
        return parentId == 0 ? null : parentId;
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.path(field).asText(null);
    }

    private static BigDecimal price(JsonNode node, String field) {
        String raw = node.path(field).asText(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return new BigDecimal(raw).setScale(2);
    }

    private static Integer stockQty(JsonNode node) {
        JsonNode stock = node.get("stock_quantity");
        return stock == null || stock.isNull() ? null : stock.asInt();
    }

    private static String brand(JsonNode node) {
        JsonNode brands = node.path("brands");
        if (brands.isArray() && !brands.isEmpty()) {
            String name = brands.get(0).path("name").asText("");
            if (!name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    private static String imageUrl(JsonNode node) {
        JsonNode images = node.path("images");
        if (images.isArray() && !images.isEmpty()) {
            String src = images.get(0).path("src").asText("");
            if (!src.isBlank()) {
                return src;
            }
        }
        String src = node.path("image").path("src").asText("");
        return src.isBlank() ? null : src;
    }

    private static Instant modifiedAt(JsonNode node) {
        String raw = node.path("date_modified_gmt").asText(null);
        return raw == null || raw.isBlank() ? null
                : LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC);
    }

    private static List<Long> categoryExternalIds(JsonNode node) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode category : node.path("categories")) {
            ids.add(category.path("id").asLong());
        }
        return ids;
    }

    private static String joinedOptions(JsonNode attributes) {
        List<String> options = new ArrayList<>();
        for (JsonNode attribute : attributes) {
            String option = attribute.path("option").asText("");
            if (!option.isBlank()) {
                options.add(option);
            }
        }
        return String.join(", ", options);
    }

    /**
     * One raw Woo response: status, headers and fully-read body.
     */
    private record WooResponse(int status, HttpHeaders headers, String body) {

        int totalPages() {
            String header = headers.getFirst(TOTAL_PAGES_HEADER);
            return header == null ? 1 : Integer.parseInt(header);
        }
    }
}
