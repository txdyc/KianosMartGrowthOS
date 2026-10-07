package com.kiano.content.shots;

/**
 * State of one checklist shot: OK (accepted media), RESHOOT (current media
 * failed QC) or MISSING (no current media).
 */
public enum ShotState {
    OK,
    RESHOOT,
    MISSING
}
