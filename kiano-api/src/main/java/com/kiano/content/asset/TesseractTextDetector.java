package com.kiano.content.asset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Runs the tesseract binary ({@code --psm 11 tsv}) and parses its output.
 * Disabled unless {@code kiano.content.precheck.tesseract-path} is set.
 */
@Component
public class TesseractTextDetector implements TextDetector {

    private final PrecheckProperties properties;

    public TesseractTextDetector(PrecheckProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean enabled() {
        String path = properties.getTesseractPath();
        return path != null && !path.isBlank();
    }

    @Override
    public List<TextDetector.OcrWord> detect(Path png) throws IOException {
        Process process = new ProcessBuilder(properties.getTesseractPath(), png.toString(),
                "stdout", "--psm", "11", "tsv")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        String tsv = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (process.waitFor() != 0) {
                throw new IOException("tesseract exited with code " + process.exitValue());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for tesseract", ex);
        }
        return TsvParser.parse(tsv);
    }
}
