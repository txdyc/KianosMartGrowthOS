package com.kiano.commerce.sync;

/**
 * Outcome of one product sync run.
 */
public record SyncResult(int categories, int products, int variations, int priceChanges,
        int markedMissing) {
}
