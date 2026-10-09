package com.kiano.content.video.h3;

import com.kiano.content.generation.GenerationJob;

/**
 * Terminal callbacks for H3 clips: implemented by the video composition
 * coordinator (Task 9), stubbed in the Task 8a/8b tests. {@code onH3Succeeded}
 * switches the EDL hook to the generated clip; {@code onH3Escalate} moves the
 * clip to the next backend or falls back to real footage.
 */
public interface H3TerminalHandler {

    void onH3Succeeded(GenerationJob job);

    void onH3Escalate(GenerationJob job, String reason);
}