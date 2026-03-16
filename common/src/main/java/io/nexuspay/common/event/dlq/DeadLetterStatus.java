package io.nexuspay.common.event.dlq;

/**
 * Lifecycle states for a dead letter queue entry.
 */
public enum DeadLetterStatus {
    /** Awaiting initial retry or manual review. */
    PENDING,
    /** Currently being reprocessed (republished to original topic). */
    RETRYING,
    /** Successfully reprocessed or manually resolved. */
    RESOLVED,
    /** Manually discarded — will not be retried. */
    DISCARDED
}
