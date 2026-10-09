package com.kiano.content.generation;

/**
 * Notified when a generation job <em>finally</em> enters FAILED - not for
 * retryable back-offs or WAITING_EXECUTOR parks. Used by the H3 escalation
 * path to move a local clip to the API backend once it has given up.
 */
public interface JobFailureListener {

    void onFailed(GenerationJob job);
}
