package io.nexuspay.marketplace.domain;

/**
 * Lifecycle states for a payout disbursement.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public enum PayoutStatus {
    PENDING,
    PROCESSING,
    PAID,
    FAILED
}
