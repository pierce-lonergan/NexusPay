package io.nexuspay.fraud.adapter.out.frm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * B-014 / L-006: FRM amount conversions must derive the scale from the currency's
 * ISO 4217 exponent, not a hardcoded /100 or ×10000 — otherwise JPY/BHD amounts
 * sent to Sift/Signifyd are 10–100× wrong.
 */
class FrmAmountsTest {

    @Test
    void majorUnits_perExponent() {
        assertThat(FrmAmounts.toMajorUnits(5000, "USD")).isEqualTo(50.0, within(1e-9));  // 2 digits
        assertThat(FrmAmounts.toMajorUnits(5000, "JPY")).isEqualTo(5000.0, within(1e-9)); // 0 digits
        assertThat(FrmAmounts.toMajorUnits(5000, "BHD")).isEqualTo(5.0, within(1e-9));    // 3 digits
    }

    @Test
    void micros_perExponent() {
        // Sift micros = 10^6 per MAJOR unit.
        assertThat(FrmAmounts.toMicros(5000, "USD")).isEqualTo(50_000_000L);    // $50.00
        assertThat(FrmAmounts.toMicros(5000, "JPY")).isEqualTo(5_000_000_000L); // ¥5000
        assertThat(FrmAmounts.toMicros(5000, "BHD")).isEqualTo(5_000_000L);     // BHD 5.000
    }

    @Test
    void unknownCurrencyAssumesTwoDecimals() {
        assertThat(FrmAmounts.toMajorUnits(5000, "ZZZ")).isEqualTo(50.0, within(1e-9));
        assertThat(FrmAmounts.toMicros(5000, "ZZZ")).isEqualTo(50_000_000L);
    }
}
