package com.kiano.content.ads;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * manifest.csv for the ad export ZIP (§10.2): UTF-8 BOM, RFC4180 quoting and
 * the exact column order {@code file_name,sku,hook,size,headline,primary_text,
 * price_snapshot,asset_id}. The price snapshot is the money number with two
 * decimals (empty for non-price variants); headline/primary text come from the
 * AD_COPY the static was rendered with.
 */
public final class ManifestCsv {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** One row of the manifest. */
    public record Row(String fileName, String sku, String hook, String size,
            @Nullable String headline, @Nullable String primaryText,
            @Nullable BigDecimal priceSnapshot, long assetId) {
    }

    private ManifestCsv() {
    }

    public static byte[] write(List<Row> rows) {
        StringBuilder csv = new StringBuilder();
        csv.append(headers()).append('\n');
        for (Row row : rows) {
            append(csv, row.fileName()).append(',');
            append(csv, row.sku()).append(',');
            append(csv, row.hook()).append(',');
            append(csv, row.size()).append(',');
            append(csv, row.headline()).append(',');
            append(csv, row.primaryText()).append(',');
            append(csv, price(row.priceSnapshot())).append(',');
            append(csv, String.valueOf(row.assetId())).append('\n');
        }
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
        return out;
    }

    private static String headers() {
        return String.join(",", List.of("file_name", "sku", "hook", "size", "headline",
                "primary_text", "price_snapshot", "asset_id"));
    }

    /** Two decimals, or blank when this variant does not display a price. */
    private static String price(@Nullable BigDecimal snapshot) {
        return snapshot == null ? "" : snapshot.setScale(2, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static StringBuilder append(StringBuilder csv, @Nullable String value) {
        csv.append(quoted(value == null ? "" : value));
        return csv;
    }

    private static String quoted(String raw) {
        if (raw.contains(",") || raw.contains("\"") || raw.contains("\n")
                || raw.contains("\r")) {
            return "\"" + raw.replace("\"", "\"\"") + "\"";
        }
        return raw;
    }
}