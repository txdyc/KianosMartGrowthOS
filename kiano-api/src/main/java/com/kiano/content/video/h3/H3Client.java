package com.kiano.content.video.h3;

import java.nio.file.Path;

/**
 * Port for the H3 provider (MiniMax): submit a clip, poll its status, download
 * the finished file. {@link MiniMaxH3Client} is the tentative WireMock-replayed
 * adapter; Appendix A of the C5 plan swaps it for the real API.
 */
public interface H3Client {

    /** Submits one image-to-video task and returns the provider's task id. */
    String submit(H3Request request);

    /** Current provider-side state of a submitted task. */
    H3TaskStatus poll(String taskId);

    /** Downloads a succeeded task's clip into {@code target}. */
    void download(H3TaskStatus.Succeeded done, Path target);
}