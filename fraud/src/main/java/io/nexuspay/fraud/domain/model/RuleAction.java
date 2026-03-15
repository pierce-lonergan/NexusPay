package io.nexuspay.fraud.domain.model;

/**
 * Action to take when a fraud rule triggers.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public enum RuleAction {
    /** Immediately block the transaction. */
    BLOCK,
    /** Flag for manual review. */
    REVIEW,
    /** Adjust the aggregate risk score by the rule's score_adjustment value. */
    SCORE_ADJUST
}
