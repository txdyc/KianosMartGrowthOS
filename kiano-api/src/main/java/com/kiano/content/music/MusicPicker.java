package com.kiano.content.music;

import com.kiano.content.video.VideoProperties;
import com.kiano.content.video.VideoType;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Picks the music track for a video (C5 T2): ACTIVE tracks whose moods contain
 * the type's configured mood win; when none match, any ACTIVE track qualifies.
 * Tracks are ranked by least-used-then-id and the caller's rotation selects
 * which one, so consecutive videos rotate through the library.
 */
@Component
public class MusicPicker {

    /** The track handed to the composition planner: id for provenance, the
     * object key as the EDL music input and the duration for looping. */
    public record MusicTrack(long id, String title, String objectKey, double durationSeconds) {
    }

    private final JdbcTemplate jdbc;
    private final VideoProperties videoProperties;

    public MusicPicker(JdbcTemplate jdbc, VideoProperties videoProperties) {
        this.jdbc = jdbc;
        this.videoProperties = videoProperties;
    }

    public Optional<MusicTrack> pick(long tenantId, VideoType type, int rotation) {
        String mood = videoProperties.getMood().get(type.wire());
        List<MusicTrack> moodMatches = active(tenantId, mood);
        List<MusicTrack> candidates = moodMatches.isEmpty() ? active(tenantId, null)
                : moodMatches;
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        int index = Math.floorMod(rotation, candidates.size());
        return Optional.of(candidates.get(index));
    }

    /** ACTIVE tracks, mood-filtered when requested, least-used then id. */
    private List<MusicTrack> active(long tenantId, @Nullable String mood) {
        String sql = """
                select m.id, m.title, m.object_key, m.duration_s,
                       (select count(*) from asset a where a.tenant_id = m.tenant_id
                          and a.spec_code = 'VIDEO_AD'
                          and jsonb_exists(a.provenance_json, 'musicTrackId')
                          and a.provenance_json ->> 'musicTrackId' = m.id::text) as used
                from music_track m
                where m.tenant_id = ? and m.status = 'ACTIVE'
                """
                + (mood == null ? "" : " and ? = any(m.moods)");
        Object[] args = mood == null ? new Object[]{tenantId}
                : new Object[]{tenantId, mood};
        return jdbc.query(sql, (rs, i) -> new Ranked(rs.getLong("id"), rs.getString("title"),
                        rs.getString("object_key"), rs.getBigDecimal("duration_s").doubleValue(),
                        rs.getLong("used")), args)
                .stream()
                .sorted(Comparator.comparingLong(Ranked::used)
                        .thenComparingLong(Ranked::id))
                .map(r -> new MusicTrack(r.id(), r.title(), r.objectKey(), r.durationSeconds()))
                .toList();
    }

    private record Ranked(long id, String title, String objectKey, double durationSeconds,
            long used) {
    }
}
