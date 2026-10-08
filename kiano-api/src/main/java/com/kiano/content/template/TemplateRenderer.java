package com.kiano.content.template;

import com.kiano.platform.web.ApiException;
import com.samskivert.mustache.Mustache;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * Renders the approved template for a code with JMustache (HTML-escaped),
 * inlines the Noto Sans fonts as base64 @font-face rules so the page makes
 * no network requests, then screenshots it with a shared headless Chromium.
 */
@Component
public class TemplateRenderer {

    private final TemplateRegistry registry;

    private final Object browserLock = new Object();
    private volatile @Nullable Playwright playwright;
    private volatile @Nullable Browser browser;

    public TemplateRenderer(TemplateRegistry registry) {
        this.registry = registry;
    }

    /** Renders the approved template to a width×height PNG. */
    public byte[] render(long tenantId, String code, Map<String, Object> model,
            int width, int height) {
        try (Page page = openPage(tenantId, code, model, width, height)) {
            return page.screenshot();
        }
    }

    /** document.body.scrollWidth after rendering, for layout tests. */
    public int bodyScrollWidth(long tenantId, String code, Map<String, Object> model,
            int width, int height) {
        try (Page page = openPage(tenantId, code, model, width, height)) {
            Object scrollWidth = page.evaluate("() => document.body.scrollWidth");
            return scrollWidth instanceof Number number ? number.intValue() : width;
        }
    }

    /** Bounding box of one {@code .ad-text} element, in CSS pixels. */
    public record Rect(double top, double bottom, double left, double right) {
    }

    /**
     * Bounding boxes of every {@code .ad-text} element after rendering, used to
     * enforce the 9:16 safe area on ad statics.
     */
    public List<Rect> textBoxes(long tenantId, String code, Map<String, Object> model,
            int width, int height) {
        return rectsFor(tenantId, code, model, width, height, ".ad-text");
    }

    /** Bounding boxes of every element matching {@code selector}. */
    public List<Rect> rectsFor(long tenantId, String code, Map<String, Object> model,
            int width, int height, String selector) {
        try (Page page = openPage(tenantId, code, model, width, height)) {
            Object boxes = page.evaluate("""
                    (selector) => Array.from(document.querySelectorAll(selector)).map(el => {
                        const r = el.getBoundingClientRect();
                        return { top: r.top, bottom: r.bottom, left: r.left, right: r.right };
                    })""", selector);
            return parseRects(boxes);
        }
    }

    private static List<Rect> parseRects(Object boxes) {
        if (!(boxes instanceof List<?> list)) {
            return List.of();
        }
        List<Rect> rects = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            try {
                rects.add(new Rect(num(map.get("top")), num(map.get("bottom")),
                        num(map.get("left")), num(map.get("right"))));
            } catch (RuntimeException ignored) {
                // a non-numeric entry cannot be a bounding box
            }
        }
        return rects;
    }

    private static double num(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            return Double.parseDouble(text);
        }
        throw new IllegalArgumentException("not a number: " + value);
    }

    /** Mustache-expanded HTML with inlined fonts, before the browser. */
    public String expanded(long tenantId, String code, Map<String, Object> model) {
        String body = registry.latestApproved(tenantId, code)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND",
                        "No approved template for " + code))
                .getBody();
        String html = Mustache.compiler().escapeHTML(true).compile(body).execute(model);
        return injectFonts(html);
    }

    /** SPEC model: a two-column spec table; null/empty fact rows are omitted. */
    public Map<String, Object> specModel(TemplateFacts facts) {
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
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("productName", facts.productName());
        model.put("model", facts.model());
        model.put("rows", rows);
        return model;
    }

    /** INFOGRAPHIC model: the product PNG as a data URI plus ≤4 benefits. */
    public Map<String, Object> infoModel(TemplateFacts facts, byte[] productPng) {
        List<String> benefits = facts.benefits() == null ? List.of()
                : facts.benefits().stream().filter(Objects::nonNull).limit(4).toList();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("productName", facts.productName());
        model.put("productImage", Base64.getEncoder().encodeToString(productPng));
        model.put("benefits", benefits);
        return model;
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

    private Page openPage(long tenantId, String code, Map<String, Object> model,
            int width, int height) {
        Page page = browser().newPage(new Browser.NewPageOptions()
                .setViewportSize(width, height));
        page.setContent(expanded(tenantId, code, model));
        page.waitForFunction("() => document.fonts.ready.then(() => true)");
        return page;
    }

    private String injectFonts(String html) {
        String fontFace = """
                @font-face { font-family: 'Noto Sans'; font-weight: 400; font-display: block;
                  src: url(data:font/woff2;base64,%s) format('woff2'); }
                @font-face { font-family: 'Noto Sans'; font-weight: 700; font-display: block;
                  src: url(data:font/woff2;base64,%s) format('woff2'); }
                """.formatted(
                Base64.getEncoder().encodeToString(
                        resource("templates/fonts/NotoSans-Regular.woff2")),
                Base64.getEncoder().encodeToString(
                        resource("templates/fonts/NotoSans-Bold.woff2")));
        return html.replace("<style>", "<style>\n" + fontFace);
    }

    private Browser browser() {
        Browser current = browser;
        if (current != null && current.isConnected()) {
            return current;
        }
        synchronized (browserLock) {
            if (browser == null || !browser.isConnected()) {
                Playwright created = Playwright.create();
                playwright = created;
                browser = created.chromium().launch();
            }
            return browser;
        }
    }

    @PreDestroy
    public void close() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    private static byte[] resource(String path) {
        try (InputStream in = TemplateRenderer.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + path);
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
