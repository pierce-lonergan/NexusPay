package io.nexuspay.billing.application.service;

import io.nexuspay.billing.domain.Price;
import io.nexuspay.billing.domain.PricingModel;
import io.nexuspay.billing.domain.Subscription;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mid-cycle plan-change money math for {@link ProrationService}. Integer division
 * and the two clamp guards (daysInPeriod <= 0, daysRemaining < 0) are classic
 * edge-bug sites — a wrong sign or off-by-one over/under-credits real money. A
 * concrete 30-day period with a change at the exact midpoint lets every prorated
 * long be asserted exactly.
 */
class ProrationServiceTest {

    private static final Instant PERIOD_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant PERIOD_END = Instant.parse("2026-01-31T00:00:00Z"); // 30 days
    private static final Instant MIDPOINT = Instant.parse("2026-01-16T00:00:00Z");    // 15 days remaining

    private final ProrationService service = new ProrationService();

    private static Price flat(long unitAmount) {
        Price p = new Price();
        p.setPricingModel(PricingModel.FLAT);
        p.setUnitAmount(unitAmount);
        return p;
    }

    private static Price perUnit(long unitAmount) {
        Price p = new Price();
        p.setPricingModel(PricingModel.PER_UNIT);
        p.setUnitAmount(unitAmount);
        return p;
    }

    private static Subscription sub(int quantity, Instant start, Instant end) {
        Subscription s = new Subscription();
        s.setQuantity(quantity);
        s.setCurrentPeriodStart(start);
        s.setCurrentPeriodEnd(end);
        return s;
    }

    @Test
    void upgradeAtMidpointChargesNetPositive() {
        Subscription s = sub(1, PERIOD_START, PERIOD_END);
        // old 3000, new 5000. credit = 3000*15/30 = 1500, charge = 5000*15/30 = 2500, net = 1000.
        ProrationService.ProrationResult r = service.calculate(s, flat(3000), flat(5000), MIDPOINT);
        assertThat(r.unusedCredit()).isEqualTo(1500);
        assertThat(r.newCharge()).isEqualTo(2500);
        assertThat(r.netAmount()).isEqualTo(1000);
        assertThat(r.isUpgrade()).isTrue();
        assertThat(r.isDowngrade()).isFalse();
    }

    @Test
    void downgradeAtMidpointCreditsNetNegative() {
        Subscription s = sub(1, PERIOD_START, PERIOD_END);
        // old 5000, new 3000. credit = 2500, charge = 1500, net = -1000.
        ProrationService.ProrationResult r = service.calculate(s, flat(5000), flat(3000), MIDPOINT);
        assertThat(r.unusedCredit()).isEqualTo(2500);
        assertThat(r.newCharge()).isEqualTo(1500);
        assertThat(r.netAmount()).isEqualTo(-1000);
        assertThat(r.isDowngrade()).isTrue();
        assertThat(r.isUpgrade()).isFalse();
    }

    @Test
    void samePriceNetsZeroAndIsNeitherUpgradeNorDowngrade() {
        Subscription s = sub(1, PERIOD_START, PERIOD_END);
        ProrationService.ProrationResult r = service.calculate(s, flat(4000), flat(4000), MIDPOINT);
        assertThat(r.netAmount()).isEqualTo(0);
        assertThat(r.isUpgrade()).isFalse();
        assertThat(r.isDowngrade()).isFalse();
    }

    @Test
    void quantityMultipliesProratedAmountsViaPriceCalculateAmount() {
        Subscription s = sub(2, PERIOD_START, PERIOD_END);
        // per-unit old 1000 * qty 2 = 2000; new 2000 * 2 = 4000.
        // credit = 2000*15/30 = 1000, charge = 4000*15/30 = 2000, net = 1000.
        ProrationService.ProrationResult r = service.calculate(s, perUnit(1000), perUnit(2000), MIDPOINT);
        assertThat(r.unusedCredit()).isEqualTo(1000);
        assertThat(r.newCharge()).isEqualTo(2000);
        assertThat(r.netAmount()).isEqualTo(1000);
    }

    @Test
    void changeAtPeriodEndGivesZeroCreditChargeAndNet() {
        Subscription s = sub(1, PERIOD_START, PERIOD_END);
        // daysRemaining = 0 -> everything 0.
        ProrationService.ProrationResult r = service.calculate(s, flat(3000), flat(5000), PERIOD_END);
        assertThat(r.unusedCredit()).isEqualTo(0);
        assertThat(r.newCharge()).isEqualTo(0);
        assertThat(r.netAmount()).isEqualTo(0);
    }

    @Test
    void changeAfterPeriodEndClampsDaysRemainingToZero() {
        Subscription s = sub(1, PERIOD_START, PERIOD_END);
        Instant afterEnd = PERIOD_END.plusSeconds(86400L * 5); // 5 days past end
        // daysRemaining would be negative -> clamped to 0, no negative credit.
        ProrationService.ProrationResult r = service.calculate(s, flat(3000), flat(5000), afterEnd);
        assertThat(r.unusedCredit()).isEqualTo(0);
        assertThat(r.newCharge()).isEqualTo(0);
        assertThat(r.netAmount()).isEqualTo(0);
    }

    @Test
    void zeroLengthPeriodClampsDaysInPeriodToOneAvoidingDivideByZero() {
        // periodStart == periodEnd -> daysInPeriod 0 -> clamped to 1.
        // changeDate before that end so daysRemaining is also 0 (both equal) -> net 0,
        // but the key assertion is that no ArithmeticException is thrown.
        Subscription s = sub(1, PERIOD_START, PERIOD_START);
        ProrationService.ProrationResult r = service.calculate(s, flat(3000), flat(5000), PERIOD_START);
        assertThat(r.unusedCredit()).isEqualTo(0);
        assertThat(r.newCharge()).isEqualTo(0);
        assertThat(r.netAmount()).isEqualTo(0);
    }

    @Test
    void zeroLengthPeriodWithRemainingTimeChargesFullAmountWithoutDivideByZero() {
        // Degenerate guard exercise: start == end (daysInPeriod clamped to 1) but the
        // change happens a full day BEFORE the end, so daysRemaining = 1. The clamp
        // must keep the division finite: credit = old*1/1, charge = new*1/1.
        Instant collapsedEnd = PERIOD_START; // start == end
        Subscription s = sub(1, PERIOD_START, collapsedEnd);
        Instant changeBefore = PERIOD_START.minusSeconds(86400L); // 1 day before
        ProrationService.ProrationResult r = service.calculate(s, flat(3000), flat(5000), changeBefore);
        assertThat(r.unusedCredit()).isEqualTo(3000);
        assertThat(r.newCharge()).isEqualTo(5000);
        assertThat(r.netAmount()).isEqualTo(2000);
    }
}
