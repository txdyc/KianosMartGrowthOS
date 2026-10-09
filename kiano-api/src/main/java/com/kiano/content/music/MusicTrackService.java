package com.kiano.content.music;

import com.kiano.content.media.MediaProperties;
import com.kiano.platform.audit.ActorType;
import com.kiano.platform.audit.AuditEntry;
import com.kiano.platform.audit.AuditLog;
import com.kiano.platform.auth.CurrentUser;
import com.kiano.platform.storage.ObjectStorage;
import com.kiano.platform.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The built-in music library (C5 T2): OWNER uploads a licensed track (mp3/m4a/
 * wav, 10-300 s, 20 MB cap) whose duration is measured with ffprobe; sha256
 * dedupes; tracks can only be RETIRED because VIDEO_AD provenance references
 * them. The list reports how many videos already use each track.
 */
@Service
public class MusicTrackService {

    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of("mp3", "m4a", "wav");
    private static final Set<String> LICENSE_TYPES =
            Set.of("CC0", "ROYALTY_FREE_COMMERCIAL", "OWNED");
    private static final Set<String> MOODS = Set.of("upbeat", "uplifting", "calm", "energetic");
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final ObjectStorage storage;
    private final AuditLog auditLog;
    private final MediaProperties mediaProperties;
    private final ObjectMapper objectMapper;

    public MusicTrackService(JdbcTemplate jdbc, ObjectStorage storage, AuditLog auditLog,
            MediaProperties mediaProperties, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.auditLog = auditLog;
        this.mediaProperties = mediaProperties;
        this.objectMapper = objectMapper;
    }

    /** Upload metadata; title, licenseSource and licenseProof are mandatory. */
    public record MusicMeta(String title, @Nullable String artist, String licenseType,
            @Nullable String licenseSource, @Nullable String licenseProof, List<String> moods) {
    }

    /** One track as shown in the library list. */
    public record MusicTrackView(long id, String title, @Nullable String artist,
            double durationSeconds, List<String> moods, String licenseType, String licenseSource,
            String licenseProof, String status, int usedCount) {
    }

    @Transactional
    public MusicTrackView upload(CurrentUser user, MultipartFile file, MusicMeta meta) {
        validateMeta(meta);
        String originalName = file.getOriginalFilename();
        String extension = extension(originalName);
        if (!EXTENSIONS.contains(extension)) {
            throw validation("Only mp3, m4a and wav files are accepted");
        }
        if (file.getSize() > MAX_BYTES) {
            throw validation("Music files must be 20 MB or smaller");
        }
        String contentType = switch (extension) {
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            default -> "audio/wav";
        };
        Path tempFile;
        try {
            tempFile = Files.createTempFile("kiano-music-", "." + extension);
            file.transferTo(tempFile);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNREADABLE_FILE",
                    "Cannot read the uploaded file: " + ex.getMessage());
        }
        try {
            double durationSeconds = probeDuration(tempFile);
            if (durationSeconds < 10 || durationSeconds > 300) {
                throw validation("Music must be between 10 and 300 seconds");
            }
            String sha256 = sha256Hex(tempFile);
            long id = jdbc.queryForObject("""
                    insert into music_track (tenant_id, title, artist, object_key, duration_s,
                                             moods, license_type, license_source, license_proof,
                                             status, sha256, created_by)
                    values (?, ?, ?, '', ?, ?::text[], ?, ?, ?, 'ACTIVE', ?, ?) returning id
                    """, Long.class, user.tenantId(), meta.title(), meta.artist(),
                    BigDecimal.valueOf(durationSeconds).setScale(2, RoundingMode.HALF_UP),
                    arrayLiteral(meta.moods()), meta.licenseType(), meta.licenseSource(),
                    meta.licenseProof(), sha256, user.userId());
            String objectKey = "t" + user.tenantId() + "/music/" + id + "." + extension;
            storage.put(objectKey, tempFile, contentType);
            jdbc.update("update music_track set object_key = ? where id = ?", objectKey, id);
            auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                    String.valueOf(user.userId()), "MUSIC_TRACK_ADDED", "music_track",
                    String.valueOf(id), null,
                    Map.of("title", meta.title(), "licenseType", meta.licenseType(),
                            "licenseSource", meta.licenseSource(), "licenseProof",
                            meta.licenseProof() == null ? "" : meta.licenseProof()),
                    null, "MUSIC"));
            return view(user.tenantId(), id, durationSeconds, meta, "ACTIVE", 0);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_TRACK",
                    "A track with the same audio file is already in the library");
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // best effort cleanup
            }
        }
    }

    @Transactional
    public void retire(CurrentUser user, long id) {
        int updated = jdbc.update("update music_track set status = 'RETIRED' "
                + "where tenant_id = ? and id = ?", user.tenantId(), id);
        if (updated == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Track not found");
        }
        auditLog.record(new AuditEntry(user.tenantId(), ActorType.USER,
                String.valueOf(user.userId()), "MUSIC_TRACK_RETIRED", "music_track",
                String.valueOf(id), null, Map.of("status", "RETIRED"), null, "MUSIC"));
    }

    public List<MusicTrackView> list(long tenantId) {
        Map<Long, Integer> usage = usageCounts(tenantId);
        return jdbc.query("""
                select id, title, artist, duration_s, moods, license_type, license_source,
                       license_proof, status
                from music_track where tenant_id = ? order by id
                """, (rs, i) -> new MusicTrackView(rs.getLong("id"), rs.getString("title"),
                        rs.getString("artist"), rs.getBigDecimal("duration_s").doubleValue(),
                        stringArray(rs.getArray("moods")), rs.getString("license_type"),
                        rs.getString("license_source"), rs.getString("license_proof"),
                        rs.getString("status"), usage.getOrDefault(rs.getLong("id"), 0)),
                tenantId);
    }

    public URI previewUri(long tenantId, long id) {
        String objectKey = jdbc.query("""
                select object_key from music_track where tenant_id = ? and id = ?
                """, (rs, i) -> rs.getString("object_key"), tenantId, id)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "NOT_FOUND", "Track not found"));
        return storage.presignGet(objectKey, Duration.ofMinutes(15));
    }

    private MusicTrackView view(long tenantId, long id, double durationSeconds, MusicMeta meta,
            String status, int usedCount) {
        return new MusicTrackView(id, meta.title(), meta.artist(), durationSeconds,
                meta.moods() == null ? List.of() : meta.moods(), meta.licenseType(),
                meta.licenseSource(), meta.licenseProof(), status, usedCount);
    }

    /** Video ads referencing this track by provenance musicTrackId. */
    private Map<Long, Integer> usageCounts(long tenantId) {
        Map<Long, Integer> usage = new LinkedHashMap<>();
        jdbc.query("""
                select (provenance_json ->> 'musicTrackId') as track_id, count(*)
                from asset where tenant_id = ? and spec_code = 'VIDEO_AD'
                  and jsonb_exists(provenance_json, 'musicTrackId')
                group by 1
                """, (rs, i) -> Map.entry(rs.getString("track_id"), rs.getInt("count")),
                tenantId)
                .forEach(entry -> {
                    try {
                        usage.put(Long.parseLong(entry.getKey()), entry.getValue());
                    } catch (NumberFormatException ignored) {
                        // non-numeric provenance value: not a track reference
                    }
                });
        return usage;
    }

    private void validateMeta(MusicMeta meta) {
        if (meta == null || isBlank(meta.title()) || isBlank(meta.licenseSource())
                || isBlank(meta.licenseProof())) {
            throw validation("title, licenseSource and licenseProof are required");
        }
        if (!LICENSE_TYPES.contains(meta.licenseType())) {
            throw validation("licenseType must be CC0, ROYALTY_FREE_COMMERCIAL or OWNED");
        }
        if (meta.moods() != null) {
            for (String mood : meta.moods()) {
                if (!MOODS.contains(mood)) {
                    throw validation("mood '" + mood + "' is not allowed");
                }
            }
        }
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    /** PostgreSQL array literal, e.g. {@code {upbeat,calm}} (empty moods → {}). */
    private static String arrayLiteral(List<String> moods) {
        if (moods == null || moods.isEmpty()) {
            return "{}";
        }
        return "{" + String.join(",", moods) + "}";
    }

    private static ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", message);
    }

    private static String extension(String originalName) {
        if (originalName == null || !originalName.contains(".")) {
            return "";
        }
        return originalName.substring(originalName.lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
    }

    /** ffprobe -show_entries format=duration, parsed for the audio track. */
    private double probeDuration(Path file) {
        Process process = null;
        try {
            process = new ProcessBuilder(mediaProperties.getFfprobePath(), "-v", "error",
                    "-show_entries", "format=duration", "-of", "json", file.toString())
                    .redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw validation("Cannot probe the audio file (timeout)");
            }
            if (process.exitValue() != 0) {
                throw validation("Cannot probe the audio file");
            }
            JsonNode format = objectMapper.readTree(output).path("format");
            double duration = format.path("duration").asDouble(-1);
            if (duration < 0) {
                throw validation("Cannot read the audio duration");
            }
            return duration;
        } catch (IOException ex) {
            throw validation("Cannot probe the audio file: " + ex.getMessage());
        } catch (InterruptedException ex) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw validation("Cannot probe the audio file (interrupted)");
        } catch (RuntimeException ex) {
            if (ex instanceof ApiException) {
                throw ex;
            }
            throw validation("Cannot probe the audio file");
        }
    }

    private static String sha256Hex(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNREADABLE_FILE",
                    "Cannot read the uploaded file: " + ex.getMessage());
        }
    }

    private static List<String> stringArray(java.sql.Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            Object raw = array.getArray();
            if (raw instanceof Object[] objects) {
                return Arrays.stream(objects).map(String::valueOf).collect(Collectors.toList());
            }
            return List.of();
        } catch (java.sql.SQLException ex) {
            return List.of();
        }
    }
}
