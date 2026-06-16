package io.nexuspay.common.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * SEC-19: exact decimal money math for percentage-of-amount calculations.
 *
 * <p>Replaces {@code (long)(amount * percent.doubleValue() / 100.0)} float math, which silently
 * drops fractional minor units and systematically biases fee collection. All arithmetic here is
 * {@link BigDecimal} so no precision is lost before the single, explicit rounding step.</p>
 *
 * <p><b>Why scale 0 is correct for ALL currencies.</b> Inputs and outputs are in <i>minor units</i>
 * — the smallest indivisible unit of the currency, which already encodes the currency exponent
 * (USD cents = exponent-2, JPY yen = exponent-0, BHD fils = exponent-3). A minor unit cannot be
 * subdivided, so the result must be a whole number of minor units regardless of currency: rounding
 * the BigDecimal quotient to scale 0 is the currency-agnostic correct behaviour.</p>
 *
 * <p><b>Why HALF_EVEN is the SEC-19 standard for fees.</b> Banker's rounding (HALF_EVEN) rounds
 * exact halves to the nearest even digit, so across many fee computations the rounding errors
 * cancel rather than accumulate. {@link RoundingMode#HALF_UP} would round every {@code .5} tie
 * upward, giving a systematic upward bias when collecting many fees <i>from</i> users — over
 * millions of transactions that bias is real money taken from customers. Callers in the fee/split
 * path therefore pass {@code HALF_EVEN}.</p>
 *
 * <p><b>Out of scope:</b> the FX conversion path ({@code FxRate} / {@code CurrencyMath}) keeps its
 * existing {@code HALF_UP} rounding and is deliberately NOT routed through this method — FX rounding
 * policy is a separate decision and is not changed by SEC-19.</p>
 */
public final class MoneyRounding {

    private MoneyRounding() {
    }

    /**
     * Computes {@code percent}% of {@code amountMinorUnits}, rounded to a whole number of minor units
     * using the supplied {@link RoundingMode}.
     *
     * <p>Pure, side-effect-free, and exact: it multiplies in {@link BigDecimal} and rounds exactly
     * once. {@code amountMinorUnits} and {@code percent} may be zero (yields {@code 0}); a negative
     * amount or percent flows through arithmetically (the divide/round still applies). {@code percent}
     * must not be {@code null}.</p>
     *
     * @param amountMinorUnits the base amount in minor currency units
     * @param percent          the percentage to apply (e.g. {@code 2.5} for 2.5%); must not be null
     * @param mode             the rounding mode (fees use {@link RoundingMode#HALF_EVEN})
     * @return {@code round(amountMinorUnits * percent / 100)} as a whole number of minor units
     * @throws NullPointerException  if {@code percent} is null
     * @throws ArithmeticException   if the rounded result overflows a {@code long}
     */
    public static long percentageOfMinorUnits(long amountMinorUnits, BigDecimal percent, RoundingMode mode) {
        if (percent == null) {
            throw new NullPointerException("percent must not be null");
        }
        return BigDecimal.valueOf(amountMinorUnits)
                .multiply(percent)
                .divide(BigDecimal.valueOf(100), 0, mode)
                .longValueExact();
    }
}
