package io.nexuspay.payment.application.port.fx;

import io.nexuspay.payment.domain.fx.FxRate;

import java.time.Instant;
import java.util.List;

/**
 * Outbound port for FX rate providers (ECB, Open Exchange Rates, custom).
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public interface FxRatePort {

    /**
     * Gets the exchange rate for a specific currency pair.
     *
     * @param fromCurrency ISO 4217 base currency
     * @param toCurrency   ISO 4217 quote currency
     * @return the current FX rate
     */
    FxRate getRate(String fromCurrency, String toCurrency);

    /**
     * Gets all available rates for a base currency.
     *
     * @param baseCurrency ISO 4217 base currency
     * @return list of FX rates
     */
    List<FxRate> getAllRates(String baseCurrency);

    /**
     * Returns the provider name (e.g., "ECB", "OpenExchangeRates").
     */
    String providerName();

    /**
     * Returns when rates were last updated from the upstream source.
     */
    Instant lastUpdated();
}
