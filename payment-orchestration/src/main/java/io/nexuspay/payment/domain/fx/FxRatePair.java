package io.nexuspay.payment.domain.fx;

import java.util.Objects;

/**
 * Represents a currency pair for FX rate lookups.
 * Convention: base/quote (e.g., EUR/USD means 1 EUR = X USD).
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public record FxRatePair(String baseCurrency, String quoteCurrency) {

    public FxRatePair {
        Objects.requireNonNull(baseCurrency, "baseCurrency must not be null");
        Objects.requireNonNull(quoteCurrency, "quoteCurrency must not be null");
        if (baseCurrency.length() != 3 || quoteCurrency.length() != 3) {
            throw new IllegalArgumentException("Currency codes must be ISO 4217 (3 characters)");
        }
        baseCurrency = baseCurrency.toUpperCase();
        quoteCurrency = quoteCurrency.toUpperCase();
        if (baseCurrency.equals(quoteCurrency)) {
            throw new IllegalArgumentException("Base and quote currencies must differ");
        }
    }

    /**
     * Returns the pair identifier, e.g., "EUR/USD".
     */
    public String pairId() {
        return baseCurrency + "/" + quoteCurrency;
    }

    /**
     * Returns the inverse pair, e.g., EUR/USD → USD/EUR.
     */
    public FxRatePair inverse() {
        return new FxRatePair(quoteCurrency, baseCurrency);
    }

    @Override
    public String toString() {
        return pairId();
    }
}
