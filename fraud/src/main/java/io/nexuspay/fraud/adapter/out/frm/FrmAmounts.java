package io.nexuspay.fraud.adapter.out.frm;

import java.util.Currency;

/**
 * Currency-exponent-aware conversions between NexusPay minor units and the unit
 * conventions each FRM provider expects.
 *
 * <p>NexusPay carries amounts in ISO 4217 minor units (10<sup>digits</sup> per
 * major unit — 2 for USD, 0 for JPY/KRW, 3 for BHD/KWD). Hardcoding a /100 or
 * ×10000 factor mis-scales every non-2-decimal currency by 10×–100×, which
 * corrupts amount-sensitive fraud models exactly where card-testing rings
 * operate.</p>
 */
final class FrmAmounts {

    private FrmAmounts() {}

    private static int fractionDigits(String currencyCode) {
        try {
            int d = Currency.getInstance(currencyCode.toUpperCase()).getDefaultFractionDigits();
            return Math.max(d, 0);
        } catch (IllegalArgumentException e) {
            return 2; // Unknown/non-ISO code — assume the common 2-decimal case.
        }
    }

    /** Major (decimal) units, e.g. Signifyd {@code orderAmount}. */
    static double toMajorUnits(long minorUnits, String currencyCode) {
        return minorUnits / Math.pow(10, fractionDigits(currencyCode));
    }

    /** Micros (10<sup>6</sup> per major unit), e.g. Sift {@code $amount}. */
    static long toMicros(long minorUnits, String currencyCode) {
        return Math.round(minorUnits * Math.pow(10, 6 - fractionDigits(currencyCode)));
    }
}
