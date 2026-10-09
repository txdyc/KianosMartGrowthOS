package com.kiano.content.video.h3;

/**
 * One H3 image-to-video request: the deterministic I2VA prompt, the reviewed
 * first-frame JPEG and the requested clip length and resolution.
 */
public record H3Request(String prompt, byte[] firstFrameJpeg, int durationSeconds, String resolution) {
}