package com.kiano.content.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tesseract TSV parsing: level-5 rows only, empty text and conf < 0 skipped. */
class TsvParserTest {

    @Test
    void parsesWordsAndConfidence_skipsEmpty() {
        String tsv = """
                level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext
                1\t1\t0\t0\t0\t0\t0\t0\t1600\t1600\t-1\t
                5\t1\t1\t1\t1\t1\t100\t200\t80\t30\t95.5\tFAN
                5\t1\t1\t1\t1\t2\t100\t240\t20\t30\t-1\t
                5\t1\t1\t1\t2\t1\t100\t280\t90\t30\t88.25\tMG-200
                """;
        List<TextDetector.OcrWord> words = TsvParser.parse(tsv);
        assertThat(words).containsExactly(
                new TextDetector.OcrWord("FAN", 95.5),
                new TextDetector.OcrWord("MG-200", 88.25));
    }
}
