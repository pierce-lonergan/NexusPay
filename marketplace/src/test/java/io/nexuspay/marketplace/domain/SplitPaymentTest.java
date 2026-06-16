package io.nexuspay.marketplace.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SEC-19: {@link SplitPayment#resolveAmounts} must compute each PERCENTAGE leg with exact BigDecimal
 * HALF_EVEN money math (not the old {@code (long)(total * percent.doubleValue() / 100.0)} float cast)
 * while the UNCHANGED reconciliation (REMAINDER rule, or leftover-to-largest-leg) keeps the invariant
 * that the legs sum EXACTLY to {@code totalAmount} — no penny created or lost.
 */
class SplitPaymentTest {

    private static SplitRule percentage(String pct) {
        return SplitRule.create("sp", "acct-" + pct, SplitType.PERCENTAGE, 0, new BigDecimal(pct), "USD");
    }

    private static SplitRule remainder() {
        return SplitRule.create("sp", "acct-rem", SplitType.REMAINDER, 0, null, "USD");
    }

    private static SplitRule fixed(long amount) {
        return SplitRule.create("sp", "acct-fix-" + amount, SplitType.FIXED, amount, null, "USD");
    }

    private static long sumLegs(SplitPayment sp) {
        return sp.getRules().stream().mapToLong(SplitRule::getCalculatedAmount).sum();
    }

    @Test
    void threeWay_333333_3334_sumsExactlyToTotal_noLeftover() {
        SplitPayment sp = SplitPayment.create("pi", "t1", 10_000, "USD");
        sp.addRule(percentage("33.33"));
        sp.addRule(percentage("33.33"));
        sp.addRule(percentage("33.34"));

        sp.resolveAmounts();

        // 3333 + 3333 + 3334 = 10000 exactly.
        assertThat(sumLegs(sp)).isEqualTo(10_000L);
        assertThat(sp.getRules().get(0).getCalculatedAmount()).isEqualTo(3333L);
        assertThat(sp.getRules().get(1).getCalculatedAmount()).isEqualTo(3333L);
        assertThat(sp.getRules().get(2).getCalculatedAmount()).isEqualTo(3334L);
    }

    @Test
    void threeWay_oddTotal_leftoverGoesToLargestLeg_sumsExactly() {
        // Total 10001: legs round to 3333/3333/3334 (allocated 10000), 1 minor unit leftover is assigned
        // to the largest leg (3334 -> 3335) by the UNCHANGED reconciliation. Sum stays EXACTLY 10001.
        SplitPayment sp = SplitPayment.create("pi", "t1", 10_001, "USD");
        sp.addRule(percentage("33.33"));
        sp.addRule(percentage("33.33"));
        sp.addRule(percentage("33.34"));

        sp.resolveAmounts();

        assertThat(sumLegs(sp)).isEqualTo(10_001L);
        assertThat(sp.getRules().get(2).getCalculatedAmount()).isEqualTo(3335L); // largest absorbs leftover
    }

    @Test
    void twoPercentPlusRemainder_sumsExactlyToTotal() {
        // 33.33% of 10000 = 3333 each (allocated 6666); REMAINDER absorbs 3334. Sum EXACTLY 10000.
        SplitPayment sp = SplitPayment.create("pi", "t1", 10_000, "USD");
        sp.addRule(percentage("33.33"));
        sp.addRule(percentage("33.33"));
        sp.addRule(remainder());

        sp.resolveAmounts();

        assertThat(sumLegs(sp)).isEqualTo(10_000L);
        assertThat(sp.getRules().get(0).getCalculatedAmount()).isEqualTo(3333L);
        assertThat(sp.getRules().get(1).getCalculatedAmount()).isEqualTo(3333L);
        assertThat(sp.getRules().get(2).getCalculatedAmount()).isEqualTo(3334L); // remainder
    }

    @Test
    void percentageLeg_fractionRoundsUp_failsOnOldFloatTruncation() {
        // 50% of 199 = 99.5 -> HALF_EVEN = 100. With a REMAINDER absorbing the rest, the percentage leg
        // is exactly 100 (old float truncation gave 99). Asserts 100 -> FAILS on the old code.
        SplitPayment sp = SplitPayment.create("pi", "t1", 199, "USD");
        sp.addRule(percentage("50"));
        sp.addRule(remainder());

        sp.resolveAmounts();

        assertThat(sp.getRules().get(0).getCalculatedAmount()).isEqualTo(100L);
        assertThat(sp.getRules().get(1).getCalculatedAmount()).isEqualTo(99L); // remainder
        assertThat(sumLegs(sp)).isEqualTo(199L);
    }

    // ---- SEC-BATCH-5c (money-math BLOCKER): HALF_EVEN per-leg rounding can round MULTIPLE legs UP, so
    // independently-rounded legs whose percentages sum to exactly 100% can sum to MORE than totalAmount.
    // The old leftover-to-largest reconciliation THREW IllegalStateException ("over-allocate") on this
    // pure rounding surplus. resolveAmounts must instead absorb the surplus and keep the EXACT sum, while
    // still throwing on a GENUINE >100% / oversized-FIXED over-allocation. ----

    @Test
    void threeWay_equalThirds_largeTotal_roundUpSurplus_doesNotThrow_sumsExactly() {
        // 33.33% of 999999 = 333299.667 -> HALF_EVEN -> 333300 (x2); 33.34% -> 333399.666 -> 333400.
        // Independently-rounded legs sum to 1_000_000 > 999_999. Old code: IllegalStateException. New
        // code: the -1 rounding surplus is reconciled against the largest leg; sum stays EXACTLY 999999.
        SplitPayment sp = SplitPayment.create("pi", "t1", 999_999, "USD");
        sp.addRule(percentage("33.33"));
        sp.addRule(percentage("33.33"));
        sp.addRule(percentage("33.34"));

        sp.resolveAmounts();

        assertThat(sumLegs(sp)).isEqualTo(999_999L);
        // No leg negative, and the surplus came off the largest (33.34%) leg.
        assertThat(sp.getRules().get(0).getCalculatedAmount()).isEqualTo(333_300L);
        assertThat(sp.getRules().get(1).getCalculatedAmount()).isEqualTo(333_300L);
        assertThat(sp.getRules().get(2).getCalculatedAmount()).isEqualTo(333_399L);
    }

    @Test
    void eightWay_equalEighths_tinyTotal_roundUpSurplus_doesNotThrow_noNegativeLeg_sumsExactly() {
        // 8 x 12.5% of 12 = 1.5 each -> HALF_EVEN -> 2 each -> 16 > 12. Old code: IllegalStateException.
        // New code: a -4 surplus is spread one unit per (largest) leg, never driving a leg negative.
        SplitPayment sp = SplitPayment.create("pi", "t1", 12, "USD");
        for (int i = 0; i < 8; i++) {
            sp.addRule(percentage("12.5"));
        }

        sp.resolveAmounts();

        assertThat(sumLegs(sp)).isEqualTo(12L);
        assertThat(sp.getRules().stream().mapToLong(SplitRule::getCalculatedAmount).min().orElseThrow())
                .as("no payout leg is driven negative").isGreaterThanOrEqualTo(0L);
    }

    @Test
    void equalThirds_onDistributableAfterFee_roundUpSurplus_doesNotThrow_sumsExactly() {
        // SplitPaymentWriter resolves on (total - platformFee). Exercise resolveAmounts directly on that
        // smaller distributable amount: 33.33/33.33/33.34 of 999_999 still reconciles to EXACTLY 999_999.
        long distributable = 999_999; // e.g. 1_000_000 total minus a 1-unit platform fee
        SplitPayment sp = SplitPayment.create("pi", "t1", distributable, "USD");
        sp.addRule(percentage("33.33"));
        sp.addRule(percentage("33.33"));
        sp.addRule(percentage("33.34"));

        sp.resolveAmounts();

        assertThat(sumLegs(sp)).isEqualTo(distributable);
    }

    @Test
    void genuineOverAllocation_percentagesExceed100_stillThrows() {
        // CONTROL: a real >100% configuration must STILL throw — the surplus-absorption must not mask a
        // genuine over-allocation. 60% + 60% of 100 = exact 120 > 100.
        SplitPayment sp = SplitPayment.create("pi", "t1", 100, "USD");
        sp.addRule(percentage("60"));
        sp.addRule(percentage("60"));

        assertThatThrownBy(sp::resolveAmounts)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("over-allocate");
    }

    @Test
    void genuineOverAllocation_oversizedFixed_stillThrows() {
        // CONTROL: FIXED legs exceeding the total must STILL throw.
        SplitPayment sp = SplitPayment.create("pi", "t1", 100, "USD");
        sp.addRule(fixed(80));
        sp.addRule(fixed(80));

        assertThatThrownBy(sp::resolveAmounts)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("over-allocate");
    }
}
