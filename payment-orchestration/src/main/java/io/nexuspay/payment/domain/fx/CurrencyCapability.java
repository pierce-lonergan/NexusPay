package io.nexuspay.payment.domain.fx;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Defines a PSP connector's currency support capabilities.
 * Used for currency-aware PSP routing.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public record CurrencyCapability(
        UUID id,
        String pspConnector,
        String currencyCode,
        boolean supportsPresentment,
        boolean supportsSettlement,
        boolean supportsDcc,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        boolean enabled
) {

    /**
     * Returns true if this PSP supports the given currency for presentment.
     */
    public boolean canPresentIn(String currency) {
        return enabled && supportsPresentment && currencyCode.equalsIgnoreCase(currency);
    }

    /**
     * Returns true if this PSP supports the given currency for settlement.
     */
    public boolean canSettleIn(String currency) {
        return enabled && supportsSettlement && currencyCode.equalsIgnoreCase(currency);
    }

    /**
     * Checks whether an amount falls within the PSP's allowed range for this currency.
     */
    public boolean isAmountInRange(BigDecimal amount) {
        if (!enabled) return false;
        if (minAmount != null && amount.compareTo(minAmount) < 0) return false;
        if (maxAmount != null && amount.compareTo(maxAmount) > 0) return false;
        return true;
    }
}
