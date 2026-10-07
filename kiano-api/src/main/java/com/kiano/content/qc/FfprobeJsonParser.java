package com.kiano.content.qc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses ffprobe JSON output (-show_streams -show_format). Takes the first
 * video stream, swaps coded dimensions when rotation metadata says +-90 or
 * +-270, computes fps from avg_frame_rate "a/b" and reads format.duration.
 */
public final class FfprobeJsonParser {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private FfprobeJsonParser() {
    }

    public static VideoInfo parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            for (JsonNode stream : root.path("streams")) {
                if (!"video".equals(stream.path("codec_type").asText(""))) {
                    continue;
                }
                int width = stream.path("width").asInt(-1);
                int height = stream.path("height").asInt(-1);
                if (width <= 0 || height <= 0) {
                    throw new UnreadableMediaException("ffprobe reported no dimensions for the video stream");
                }
                double duration = root.path("format").path("duration").asDouble(-1);
                if (duration < 0) {
                    throw new UnreadableMediaException("ffprobe reported no format duration");
                }
                return new VideoInfo(
                        rotationSwaps(stream) ? height : width,
                        rotationSwaps(stream) ? width : height,
                        duration,
                        fps(stream.path("avg_frame_rate").asText("")));
            }
            throw new UnreadableMediaException("No video stream found in ffprobe output");
        } catch (UnreadableMediaException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new UnreadableMediaException("Cannot parse ffprobe output: " + ex.getMessage());
        }
    }

    /**
     * Rotation comes from side_data_list[].rotation when present, otherwise
     * from tags.rotate. +-90 and +-270 swap width/height.
     */
    private static boolean rotationSwaps(JsonNode stream) {
        double rotation = 0;
        boolean found = false;
        for (JsonNode sideData : stream.path("side_data_list")) {
            JsonNode node = sideData.path("rotation");
            if (node.isNumber() || node.isTextual() && !node.asText("").isBlank()) {
                rotation = node.asDouble(0);
                found = true;
                break;
            }
        }
        if (!found) {
            JsonNode tag = stream.path("tags").path("rotate");
            if (!tag.isMissingNode() && !tag.isNull()) {
                rotation = tag.asDouble(0);
            }
        }
        int degrees = (int) Math.round(Math.abs(rotation));
        return degrees % 180 == 90;
    }

    private static double fps(String avgFrameRate) {
        if (avgFrameRate == null || avgFrameRate.isBlank()) {
            throw new UnreadableMediaException("ffprobe reported no avg_frame_rate");
        }
        String[] parts = avgFrameRate.split("/");
        double value;
        try {
            value = Double.parseDouble(parts[0]);
            if (parts.length > 1) {
                double denominator = Double.parseDouble(parts[1]);
                if (denominator == 0) {
                    throw new UnreadableMediaException("avg_frame_rate has a zero denominator: " + avgFrameRate);
                }
                value /= denominator;
            }
        } catch (NumberFormatException ex) {
            throw new UnreadableMediaException("Cannot parse avg_frame_rate: " + avgFrameRate);
        }
        if (value <= 0) {
            throw new UnreadableMediaException("avg_frame_rate is not positive: " + avgFrameRate);
        }
        return value;
    }
}
