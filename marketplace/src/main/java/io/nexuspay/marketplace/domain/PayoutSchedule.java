package io.nexuspay.marketplace.domain;

/**
 * Frequency at which payouts are disbursed to connected accounts.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public enum PayoutSchedule {
    DAILY,
    WEEKLY,
    MONTHLY,
    MANUAL
}
