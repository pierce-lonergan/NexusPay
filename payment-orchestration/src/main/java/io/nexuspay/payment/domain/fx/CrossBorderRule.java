package io.nexuspay.payment.domain.fx;

import java.math.BigDecimal;

/**
 * Defines a cross-border compliance rule for a specific country or route.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public record CrossBorderRule(
        String sourceCountry,
        String destinationCountry,
        BigDecimal reportingThreshold,
        String reportingCurrency,
        boolean requiresEnhancedDueDiligence,
        boolean blocked
) {

    /**
     * Checks if a transaction amount requires regulatory reporting.
     */
    public boolean requiresReporting(BigDecimal amount) {
        if (reportingThreshold == null) return false;
        return amount.compareTo(reportingThreshold) >= 0;
    }
}
