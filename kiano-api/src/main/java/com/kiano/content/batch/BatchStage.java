package com.kiano.content.batch;

/**
 * The stage of a batch item (C6 Task 3). A stage is always derived from the
 * actual state of the product's content - never stored - so work done outside
 * the batch (a manual fact lock, a review decision on the product page) moves
 * the item forward as well. The declaration order is the progression order.
 */
public enum BatchStage {
    /** G1 has not passed: the required shots are not all ACCEPTED yet. */
    WAITING_SHOTS,
    /** The image pipeline is running. */
    IMAGES,
    /** No usable fact sheet yet and the draft is being generated. */
    FACTS_DRAFTING,
    /** A fact draft waits for the operator to confirm and lock it. */
    AWAITING_FACT_LOCK,
    /** Facts are locked and the copy/info/spec content is being generated. */
    GENERATING,
    /** Content waits for review. */
    IN_REVIEW,
    /** Staging publish requested or running. */
    STAGING,
    /** Staging is APPLIED; the SKU is ready for the bulk production publish. */
    READY_FOR_PRODUCTION,
    /** Production publish requested or running. */
    PRODUCTION,
    /** Production is APPLIED. */
    DONE,
    /** A human has to fix something; see the blocked reason. */
    BLOCKED
}