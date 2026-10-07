package com.kiano.content.profile;

import com.kiano.content.ContentTier;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PATCH /api/v1/products/{id}/profile — set the content tier of a top-level
 * product (OPERATOR).
 */
@RestController
@RequestMapping("/api/v1/products/{id}/profile")
public class ProfileController {

    private final ProductProfileService profileService;

    public ProfileController(ProductProfileService profileService) {
        this.profileService = profileService;
    }

    public record ProfilePatchRequest(@NotBlank String contentTier) {
    }

    @PatchMapping
    @PreAuthorize("hasRole('OPERATOR')")
    public Map<String, Object> patch(CurrentUser user, @PathVariable("id") long productId,
            @Valid @RequestBody ProfilePatchRequest request) {
        ContentTier tier = parseTier(request.contentTier());
        profileService.setTier(user, productId, tier);
        return Map.of("productId", productId, "contentTier", tier.name());
    }

    private static ContentTier parseTier(String raw) {
        try {
            return ContentTier.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "contentTier must be HERO or STANDARD");
        }
    }
}
