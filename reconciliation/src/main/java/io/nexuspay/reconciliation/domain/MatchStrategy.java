package io.nexuspay.reconciliation.domain;

/**
 * Strategy used for matching settlement records against payments and ledger entries.
 *
 * @since 0.2.0 (Sprint 2.3)
 */
public enum MatchStrategy {

    /**
     * Amount, currency, and reference ID must match exactly.
     */
    EXACT,

    /**
     * Amount within configurable tolerance (e.g., +/- $0.01 for rounding),
     * date within configurable range.
     */
    FUZZY,

    /**
     * Settlement date within N days of payment date.
     * PSPs settle on different schedules (T+1, T+2, T+7).
     */
    DATE_RANGE
}
