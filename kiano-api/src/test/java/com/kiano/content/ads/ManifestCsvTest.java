package com.kiano.content.ads;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * manifest.csv layout: UTF-8 BOM, the exact eight-column header, RFC4180
 * quoting (commas, quotes and newlines) and the two-decimal price snapshot
 * that stays blank on non-price variants.
 */
class ManifestCsvTest {

    @Test
    void write_hasBomAndHeader() {
        byte[] bytes = ManifestCsv.write(List.of());

        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        String body = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(body).startsWith("file_name,sku,hook,size,headline,primary_text,"
                + "price_snapshot,asset_id\n");
    }

    @Test
    void write_quotesCommaQuoteAndNewlineValues() {
        byte[] bytes = ManifestCsv.write(List.of(new ManifestCsv.Row("MG_a.jpg", "MG-A",
                "pricehook", "1080x1080", "Value, wow", "He said \"hi\"\nagain",
                BigDecimal.valueOf(249), 7L)));

        String body = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(body).contains("MG_a.jpg,MG-A,pricehook,1080x1080,");
        assertThat(body).contains("\"Value, wow\"");               // comma quoted
        assertThat(body).contains("\"He said \"\"hi\"\"\nagain\""); // quote & newline
        assertThat(body).endsWith("249.00,7\n");
    }

    @Test
    void priceSnapshot_blankWhenNull() {
        byte[] bytes = ManifestCsv.write(List.of(new ManifestCsv.Row("MG_b.jpg", "MG-B",
                "problem", "1080x1080", null, null, null, 8L)));

        String body = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(body).contains(",1080x1080,,,,8");
    }

    @Test
    void priceSnapshot_integerPrice_becomesTwoDecimals() {
        var row = new ManifestCsv.Row("MG_c.jpg", "MG-C", "pricehook", "1080x1350",
                "h", "p", BigDecimal.valueOf(299), 9L);

        byte[] bytes = ManifestCsv.write(List.of(row));
        String body = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);

        assertThat(body).contains("299.00,9");
    }
}