package io.nexuspay.common.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SEC-19: {@link MoneyRounding#percentageOfMinorUnits} is the exact-decimal replacement for the float
 * money math {@code (long)(amount * percent.doubleValue() / 100.0)}. These tests pin its contract:
 * exact BigDecimal arithmetic, scale-0 rounding (minor units), HALF_EVEN banker's rounding, and the
 * null/zero guards.
 */
class MoneyRoundingTest {

    @Test
    void exactPercentage_noRounding() {
        // 10% of 10000 = 1000 exactly.
        assertThat(MoneyRounding.percentageOfMinorUnits(10_000, new BigDecimal("10"), RoundingMode.HALF_EVEN))
                .isEqualTo(1000L);
    }

    @Test
    void fractionalMinorUnits_areRounded_notTruncated() {
        // 2.5% of 10001 = 250.025 -> HALF_EVEN rounds to 250. The OLD float cast (long)(250.025) = 250
        // here too, but see bankersRounding_tie_* for the case that separates the implementations.
        assertThat(MoneyRounding.percentageOfMinorUnits(10_001, new BigDecimal("2.5"), RoundingMode.HALF_EVEN))
                .isEqualTo(250L);
    }

    @Test
    void bankersRounding_tieRoundsToEven_down() {
        // 2.5 exactly: HALF_EVEN -> nearest even = 2. HALF_UP would give 3.
        long even = MoneyRounding.percentageOfMinorUnits(50, new BigDecimal("5"), RoundingMode.HALF_EVEN);
        long up = MoneyRounding.percentageOfMinorUnits(50, new BigDecimal("5"), RoundingMode.HALF_UP);
        assertThat(even).isEqualTo(2L);
        assertThat(up).isEqualTo(3L);
        assertThat(even).isNotEqualTo(up);
    }

    @Test
    void bankersRounding_tieRoundsToEven_up() {
        // 3.5 exactly: HALF_EVEN -> nearest even = 4 (rounds UP to even); HALF_UP also 4. Confirms the
        // round-to-even direction, not merely round-down.
        long even = MoneyRounding.percentageOfMinorUnits(70, new BigDecimal("5"), RoundingMode.HALF_EVEN);
        assertThat(even).isEqualTo(4L);
    }

    @Test
    void zeroPercent_isAllowed_returnsZero() {
        assertThat(MoneyRounding.percentageOfMinorUnits(123_456, BigDecimal.ZERO, RoundingMode.HALF_EVEN))
                .isZero();
    }

    @Test
    void zeroAmount_returnsZero() {
        assertThat(MoneyRounding.percentageOfMinorUnits(0, new BigDecimal("2.5"), RoundingMode.HALF_EVEN))
                .isZero();
    }

    @Test
    void largeRealisticAmount_exactBigDecimal() {
        // 2.9% of 1,000,000,00 minor units (= $1,000,000.00) = 2,900,000. Float math at this magnitude
        // risks ULP drift; BigDecimal is exact.
        assertThat(MoneyRounding.percentageOfMinorUnits(100_000_000L, new BigDecimal("2.9"), RoundingMode.HALF_EVEN))
                .isEqualTo(2_900_000L);
    }

    @Test
    void nullPercent_throws() {
        assertThatThrownBy(() ->
                MoneyRounding.percentageOfMinorUnits(1000, null, RoundingMode.HALF_EVEN))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("percent");
    }
}
