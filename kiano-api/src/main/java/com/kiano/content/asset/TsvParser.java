package com.kiano.content.asset;

import java.util.ArrayList;
import java.util.List;

/** Parses tesseract TSV output: level-5 rows with conf >= 0 and non-empty text. */
public final class TsvParser {

    private static final int TEXT_COLUMN = 11;
    private static final int CONF_COLUMN = 10;

    private TsvParser() {
    }

    public static List<TextDetector.OcrWord> parse(String tsv) {
        List<TextDetector.OcrWord> words = new ArrayList<>();
        for (String line : tsv.split("\\R")) {
            String[] cols = line.split("\t", -1);
            if (cols.length <= TEXT_COLUMN || !"5".equals(cols[0])) {
                continue;
            }
            String text = cols[TEXT_COLUMN].trim();
            double confidence = Double.parseDouble(cols[CONF_COLUMN]);
            if (text.isEmpty() || confidence < 0) {
                continue;
            }
            words.add(new TextDetector.OcrWord(text, confidence));
        }
        return words;
    }
}
