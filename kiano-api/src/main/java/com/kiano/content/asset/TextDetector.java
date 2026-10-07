package com.kiano.content.asset;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** OCR backend used to spot AI-rendered text in scene images. */
public interface TextDetector {

    /** True when the OCR binary is configured. */
    boolean enabled();

    /** Runs OCR on the given PNG and returns the recognised words. */
    List<OcrWord> detect(Path png) throws IOException;

    /** One recognised word with its confidence (0-100). */
    record OcrWord(String text, double confidence) {
    }
}
