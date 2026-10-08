package com.kiano.content.ads;

import com.kiano.commerce.ProductView;
import com.kiano.platform.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * The price that an ad static shows (§7.3): always the current {@code price};
 * the struck-out regular price and an "Ends {d MMM}" label appear only while
 * a real promotion is running - sale price lower than regular AND a future end
 * date. The check uses the render time, so an expired promotion stops showing
 * strikethrough even before Woo syncs the price back (Review Focus 1).
 */
public record PriceDisplay(String current, @Nullable String strike,
        @Nullable String endsLabel, BigDecimal snapshot) {

    private static final ZoneId ACCRA = ZoneId.of("Africa/Accra");
    private static final DateTimeFormatter ENDS_FORMAT =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ACCRA);

    public static PriceDisplay of(ProductView product, Instant now) {
        BigDecimal current = product.price() != null ? product.price()
                : product.regularPrice();
        if (current == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PRICE_MISSING",
                    "This product has no price to display on the ad");
        }
        boolean saleRunning = isSaleRunning(product, now);
        String strike = saleRunning ? GhsFormat.format(product.regularPrice()) : null;
        String endsLabel = saleRunning ? "Ends " + ENDS_FORMAT.format(product.saleToAt()) : null;
        return new PriceDisplay(GhsFormat.format(current), strike, endsLabel, current);
    }

    private static boolean isSaleRunning(ProductView product, Instant now) {
        if (product.salePrice() == null || product.regularPrice() == null
                || product.salePrice().compareTo(product.regularPrice()) >= 0) {
            return false;
        }
        return product.saleToAt() != null && product.saleToAt().isAfter(now);
    }
}