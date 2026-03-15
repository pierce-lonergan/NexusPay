package io.nexuspay.fraud.domain.model;

/**
 * Types of fraud detection rules supported by the rules engine.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public enum RuleType {
    /** Limits transaction frequency within a time window. */
    VELOCITY,
    /** Blocks or reviews transactions above a monetary threshold. */
    AMOUNT_THRESHOLD,
    /** Restricts transactions from specific countries or regions. */
    GEO_RESTRICTION,
    /** Checks card BIN ranges against known high-risk lists. */
    BIN_CHECK,
    /** Evaluates device fingerprint reputation and anomalies. */
    DEVICE_FINGERPRINT
}
