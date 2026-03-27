package io.nexuspay.marketplace.domain;

/**
 * Lifecycle states for a split payment distribution.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public enum SplitPaymentStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
