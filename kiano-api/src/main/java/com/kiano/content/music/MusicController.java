package com.kiano.content.music;

import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.web.ApiException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Music library endpoints (C5 T2): list (VIEWER), OWNER-only upload and retire,
 * and a preview redirect to a presigned URL.
 */
@RestController
@RequestMapping("/api/v1/content/music")
public class MusicController {

    private final MusicTrackService musicService;

    public MusicController(MusicTrackService musicService) {
        this.musicService = musicService;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<MusicTrackService.MusicTrackView> list(CurrentUser user) {
        return musicService.list(user.tenantId());
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public MusicTrackService.MusicTrackView upload(CurrentUser user,
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "artist", required = false) String artist,
            @RequestParam("licenseType") String licenseType,
            @RequestParam("licenseSource") String licenseSource,
            @RequestParam("licenseProof") String licenseProof,
            @RequestParam(value = "moods", required = false) List<String> moods) {
        return musicService.upload(user, file, new MusicTrackService.MusicMeta(title, artist,
                licenseType, licenseSource, licenseProof, moods));
    }

    @PostMapping("/{id}/retire")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<Void> retire(CurrentUser user, @PathVariable("id") long id) {
        musicService.retire(user, id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/preview")
    @PreAuthorize("hasRole('VIEWER')")
    public ResponseEntity<Void> preview(CurrentUser user, @PathVariable("id") long id) {
        URI uri = musicService.previewUri(user.tenantId(), id);
        return ResponseEntity.status(HttpStatus.FOUND).location(uri).build();
    }
}
