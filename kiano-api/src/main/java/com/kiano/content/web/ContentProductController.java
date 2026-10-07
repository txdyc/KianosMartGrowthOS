package com.kiano.content.web;

import com.kiano.content.ContentTier;
import com.kiano.content.shots.ContentProductSummary;
import com.kiano.content.shots.ProductShotStatus;
import com.kiano.content.shots.ShotStatusService;
import com.kiano.platform.auth.CurrentUser;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Content-side product list and per-product shot checklist (VIEWER).
 */
@RestController
@RequestMapping("/api/v1/content/products")
public class ContentProductController {

    private final ShotStatusService shotStatusService;

    public ContentProductController(ShotStatusService shotStatusService) {
        this.shotStatusService = shotStatusService;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<ContentProductSummary> list(CurrentUser user,
            @RequestParam(name = "tier", required = false) ContentTier tier,
            @RequestParam(name = "q", required = false) String q) {
        return shotStatusService.summaries(user.tenantId(), tier, q);
    }

    @GetMapping("/{id}/shots")
    @PreAuthorize("hasRole('VIEWER')")
    public ProductShotStatus shots(CurrentUser user, @PathVariable("id") long productId) {
        return shotStatusService.statusFor(user.tenantId(), productId, true);
    }
}
