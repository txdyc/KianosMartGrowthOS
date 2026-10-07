package com.kiano.content.web;

import com.kiano.content.media.ImportResult;
import com.kiano.content.media.MediaImportService;
import com.kiano.platform.auth.CurrentUser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * POST /api/v1/content/source-media — one shot file per request (OPERATOR).
 * The upload lands in a temp file that is always deleted afterwards.
 */
@RestController
@RequestMapping("/api/v1/content/source-media")
public class SourceMediaController {

    private final MediaImportService importService;

    public SourceMediaController(MediaImportService importService) {
        this.importService = importService;
    }

    @PostMapping
    @PreAuthorize("hasRole('OPERATOR')")
    public ImportResult upload(CurrentUser user, @RequestParam("file") MultipartFile file) throws IOException {
        Path tempFile = Files.createTempFile("kiano-import-", ".part");
        try {
            file.transferTo(tempFile);
            return importService.importFile(user, file.getOriginalFilename(), tempFile,
                    file.getContentType());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
