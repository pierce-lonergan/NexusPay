package io.nexuspay.payment.domain.fx;

/**
 * Represents a country-level restriction for cross-border transactions.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public record CountryRestriction(
        String countryCode,
        RestrictionType type,
        String reason
) {

    public enum RestrictionType {
        /** Full sanctions — all transactions blocked */
        SANCTIONED,
        /** Requires additional compliance checks */
        HIGH_RISK,
        /** Subject to enhanced reporting requirements */
        REPORTING_REQUIRED
    }

    /**
     * Returns true if this restriction blocks the transaction entirely.
     */
    public boolean isBlocking() {
        return type == RestrictionType.SANCTIONED;
    }
}
