package io.nexuspay.marketplace.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-19: {@link PlatformFee#calculateFee} must use exact BigDecimal HALF_EVEN money math, not the old
 * {@code (long)(amount * percent.doubleValue() / 100.0)} float cast. Several of these cases FAIL on the
 * old float-truncation code (a truncating cast drops the fractional minor unit downward, silently
 * under-charging or losing money), so this suite is a regression gate on the float math.
 */
class PlatformFeeTest {

    @Test
    void calculateFee_exactPercentage_plusFixed() {
        // 15% of 10000 = 1500, + 30 fixed = 1530.
        assertThat(PlatformFee.calculateFee(10_000, new BigDecimal("15"), 30)).isEqualTo(1530L);
    }

    @Test
    void calculateFee_fractionalRoundsUp_failsOnOldFloatTruncation() {
        // 50% of 199 = 99.5 -> HALF_EVEN rounds to 100 (nearest even). The OLD (long)(199*50.0/100.0)
        // truncated 99.5 to 99, LOSING a minor unit. This asserts 100 -> FAILS on the old code.
        assertThat(PlatformFee.calculateFee(199, new BigDecimal("50"), 0)).isEqualTo(100L);
    }

    @Test
    void calculateFee_tinyAmount_fractionRoundsUp_failsOnOldFloatTruncation() {
        // 1% of 99 = 0.99 -> rounds to 1. OLD (long)(0.99) = 0. Asserts 1 -> FAILS on the old code.
        assertThat(PlatformFee.calculateFee(99, new BigDecimal("1"), 0)).isEqualTo(1L);
    }

    @Test
    void calculateFee_bankersTie_roundsToEven() {
        // 5% of 50 = 2.50 exactly -> HALF_EVEN = 2 (nearest even). (HALF_UP would be 3.) The old float
        // truncation also gave 2 here, so this pins the banker's-rounding direction rather than the
        // float regression.
        assertThat(PlatformFee.calculateFee(50, new BigDecimal("5"), 0)).isEqualTo(2L);
    }

    @Test
    void calculateFee_zeroPercent_onlyFixed() {
        assertThat(PlatformFee.calculateFee(10_000, BigDecimal.ZERO, 250)).isEqualTo(250L);
    }

    @Test
    void calculateFee_largeRealisticAmount() {
        // 2.9% of $1,000,000.00 (100,000,000 minor) = 2,900,000, + 30 fixed.
        assertThat(PlatformFee.calculateFee(100_000_000L, new BigDecimal("2.9"), 30))
                .isEqualTo(2_900_030L);
    }

    @Test
    void calculateFee_adversarial_2point5pct_of_10001() {
        // 2.5% of 10001 = 250.025 -> 250. Documents the exact-decimal path on an odd amount.
        assertThat(PlatformFee.calculateFee(10_001, new BigDecimal("2.5"), 0)).isEqualTo(250L);
    }
}
