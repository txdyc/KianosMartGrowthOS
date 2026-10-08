package com.kiano.content.template;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug endpoint for the C2 acceptance smoke check: renders PAGE_SPEC with
 * canned facts through the real Playwright Chromium inside the api image,
 * proving the container can produce template pages. Only exists under the
 * local profile (never enabled in normal runs); still requires an
 * authenticated session like every other /api/v1 route.
 */
@RestController
@Profile("local")
public class TemplateDebugController {

    private final TemplateRenderer renderer;
    private final JdbcTemplate jdbcTemplate;

    public TemplateDebugController(TemplateRenderer renderer, JdbcTemplate jdbcTemplate) {
        this.renderer = renderer;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping(value = "/api/v1/debug/template/spec.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> specPng() {
        Long tenantId = jdbcTemplate.queryForObject("select min(id) from tenant", Long.class);
        TemplateFacts facts = new TemplateFacts("Morgan Steam Iron", "MI-2000", "Home Appliances",
                "300 ml", 2000, "220-240V", "Ceramic soleplate", "Turquoise Blue", "1 year",
                List.of("Steam iron", "Measuring cup", "Instruction manual"),
                List.of("Ceramic soleplate", "Vertical steam", "Self-cleaning"),
                List.of("Iron your clothes quickly", "Protect delicate fabrics"));
        byte[] png = renderer.render(tenantId, "PAGE_SPEC", renderer.specModel(facts), 1600, 1600);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }
}
