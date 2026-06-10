package io.nexuspay.payment.domain.fx;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * FX rate for a currency pair from a specific provider.
 * Rates are stored with 8 decimal precision.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public record FxRate(
        FxRatePair pair,
        BigDecimal rate,
        BigDecimal inverseRate,
        String provider,
        Instant timestamp
) {

    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);

    public FxRate {
        Objects.requireNonNull(pair, "pair must not be null");
        Objects.requireNonNull(rate, "rate must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("FX rate must be positive");
        }
        if (inverseRate == null) {
            inverseRate = BigDecimal.ONE.divide(rate, MC);
        }
    }

    /**
     * Create an FxRate with auto-computed inverse.
     */
    public static FxRate of(String baseCurrency, String quoteCurrency, BigDecimal rate, String provider) {
        return new FxRate(
                new FxRatePair(baseCurrency, quoteCurrency),
                rate,
                BigDecimal.ONE.divide(rate, MC),
                provider,
                Instant.now()
        );
    }

    /**
     * Converts an amount from base currency to quote currency, honoring each
     * currency's ISO 4217 exponent (so e.g. USD→JPY is not 100× off).
     *
     * @param amountMinorUnits amount in BASE-currency minor units
     * @return converted amount in QUOTE-currency minor units
     */
    public long convert(long amountMinorUnits) {
        return CurrencyMath.convert(amountMinorUnits, pair.baseCurrency(), pair.quoteCurrency(), rate);
    }

    /**
     * Applies a markup in basis points (bps) to the rate.
     * E.g., 50 bps = 0.5% markup.
     */
    public FxRate withMarkup(int markupBps) {
        if (markupBps == 0) return this;
        BigDecimal markupFactor = BigDecimal.ONE.add(
                BigDecimal.valueOf(markupBps).divide(BigDecimal.valueOf(10000), MC)
        );
        BigDecimal markedUpRate = rate.multiply(markupFactor, MC);
        return new FxRate(pair, markedUpRate, BigDecimal.ONE.divide(markedUpRate, MC), provider, timestamp);
    }

    /**
     * Returns true if this rate is older than the specified duration.
     */
    public boolean isStale(java.time.Duration maxAge) {
        return Instant.now().isAfter(timestamp.plus(maxAge));
    }
}
