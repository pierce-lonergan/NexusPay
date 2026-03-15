package io.nexuspay.fraud.domain.model;

/**
 * Fraud risk decision outcome.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public enum RiskDecision {
    /** Payment is allowed to proceed. */
    ALLOW,
    /** Payment requires manual review before proceeding. */
    REVIEW,
    /** Payment is blocked due to fraud risk. */
    BLOCK
}
