package io.nexuspay.payment.domain.fx;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Currency-exponent-aware minor-unit conversion.
 *
 * <p>Amounts are ISO 4217 minor units (10<sup>digits</sup> per major unit — 2
 * for USD, 0 for JPY/KRW, 3 for BHD/KWD). FX rates are quoted in MAJOR units
 * (1 base major = {@code rate} quote major). Multiplying minor units by a
 * major-unit rate is only correct when base and quote share the same exponent;
 * for e.g. USD→JPY it is 100× wrong. This helper converts through major units
 * using each currency's own exponent.</p>
 */
final class CurrencyMath {

    private CurrencyMath() {}

    private static final MathContext MC = new MathContext(18, RoundingMode.HALF_UP);

    static int fractionDigits(String currencyCode) {
        try {
            return Math.max(Currency.getInstance(currencyCode.toUpperCase()).getDefaultFractionDigits(), 0);
        } catch (RuntimeException e) {
            return 2; // Unknown/non-ISO code — assume the common 2-decimal case.
        }
    }

    /**
     * Converts {@code amountMinorUnits} of {@code fromCurrency} into the minor
     * units of {@code toCurrency} at {@code rate} (quote-major per base-major).
     */
    static long convert(long amountMinorUnits, String fromCurrency, String toCurrency, BigDecimal rate) {
        BigDecimal major = BigDecimal.valueOf(amountMinorUnits).movePointLeft(fractionDigits(fromCurrency));
        BigDecimal convertedMajor = major.multiply(rate, MC);
        return convertedMajor.movePointRight(fractionDigits(toCurrency))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
