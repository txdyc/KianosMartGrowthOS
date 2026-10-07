package com.kiano.content.web;

import com.kiano.content.ContentTier;
import com.kiano.content.shots.ReshootLine;
import com.kiano.content.shots.ShotStatusService;
import com.kiano.platform.auth.CurrentUser;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reshoot list as JSON and as a downloadable CSV (VIEWER). The CSV starts
 * with a UTF-8 BOM so Excel opens the Chinese guidance correctly.
 */
@RestController
public class ReshootController {

    private final ShotStatusService shotStatusService;

    public ReshootController(ShotStatusService shotStatusService) {
        this.shotStatusService = shotStatusService;
    }

    @GetMapping("/api/v1/content/reshoot-list")
    @PreAuthorize("hasRole('VIEWER')")
    public List<ReshootLine> list(CurrentUser user,
            @RequestParam(name = "tier", required = false) ContentTier tier) {
        return shotStatusService.reshootList(user.tenantId(), tier);
    }

    @GetMapping("/api/v1/content/reshoot-list.csv")
    @PreAuthorize("hasRole('VIEWER')")
    public ResponseEntity<byte[]> csv(CurrentUser user,
            @RequestParam(name = "tier", required = false) ContentTier tier) throws IOException {
        String csv = renderCsv(shotStatusService.reshootList(user.tenantId(), tier));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"reshoot-list.csv\"")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static String renderCsv(List<ReshootLine> reshootLines) throws IOException {
        StringWriter buffer = new StringWriter();
        buffer.write('\uFEFF');
        try (CSVPrinter printer = new CSVPrinter(buffer, CSVFormat.RFC4180)) {
            printer.printRecord("sku", "product_name", "tier", "shot_code", "state", "reasons",
                    "guidance_zh", "guidance_en");
            for (ReshootLine line : reshootLines) {
                printer.printRecord(line.sku(), line.productName(), line.tier().name(), line.shotCode(),
                        line.state().name(),
                        line.reasons().stream().map(Enum::name).collect(Collectors.joining("|")),
                        line.guidanceZh(), line.guidanceEn());
            }
        }
        return buffer.toString();
    }
}
