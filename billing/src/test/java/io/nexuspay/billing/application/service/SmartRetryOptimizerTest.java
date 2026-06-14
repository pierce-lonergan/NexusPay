package io.nexuspay.billing.application.service;

import io.nexuspay.billing.config.BillingConfig;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch-coverage tests for {@link SmartRetryOptimizer}: the passthrough when
 * smart retry is disabled, card-type hour math with its clamps, timezone
 * parsing/fallback, weekend avoidance, and the past-time +1-day roll. Times far
 * in the future are used so the past-time guard does not fire and results stay
 * deterministic (no {@code now()} flakiness).
 */
class SmartRetryOptimizerTest {

    /** All clock math is asserted in UTC so the instant's calendar day == the zone's. */
    private static final ZoneId UTC = ZoneId.of("UTC");

    // Far-future deterministic anchors (verified): Sat / Sun / Mon / Fri.
    private static final Instant FUTURE_SATURDAY = Instant.parse("2030-06-15T03:00:00Z");
    private static final Instant FUTURE_SUNDAY = Instant.parse("2030-06-16T03:00:00Z");
    private static final Instant FUTURE_FRIDAY = Instant.parse("2030-06-14T03:00:00Z");

    private static BillingConfig.BillingProperties props(boolean smartEnabled, int optimalHour) {
        BillingConfig.BillingProperties p = new BillingConfig.BillingProperties();
        p.getDunning().setSmartRetryEnabled(smartEnabled);
        p.getDunning().setOptimalHour(optimalHour);
        return p;
    }

    private static SmartRetryOptimizer optimizer(boolean smartEnabled, int optimalHour) {
        return new SmartRetryOptimizer(props(smartEnabled, optimalHour));
    }

    private static int hourInUtc(Instant i) {
        return i.atZone(UTC).getHour();
    }

    // ---- disabled passthrough ----

    @Test
    void disabledReturnsBaseUnchanged() {
        SmartRetryOptimizer opt = optimizer(false, 10);
        Instant base = Instant.parse("2030-06-17T13:37:42Z");
        assertThat(opt.optimize(base, "UTC", "debit")).isEqualTo(base);
    }

    // ---- target hour & minute normalization ----

    @Test
    void enabledNormalizesToTargetHourTopOfHour() {
        SmartRetryOptimizer opt = optimizer(true, 10);
        // Friday far in the future, UTC zone, no card type -> 10:00:00.000Z same day.
        Instant result = opt.optimize(FUTURE_FRIDAY, "UTC", null);
        ZonedDateTime z = result.atZone(UTC);
        assertThat(z.getHour()).isEqualTo(10);
        assertThat(z.getMinute()).isZero();
        assertThat(z.getSecond()).isZero();
        assertThat(z.getNano()).isZero();
    }

    // ---- card-type hour math ----

    @Test
    void debitRetriesTwoHoursEarlier() {
        assertThat(hourInUtc(optimizer(true, 10).optimize(FUTURE_FRIDAY, "UTC", "debit"))).isEqualTo(8);
    }

    @Test
    void creditRetriesOneHourLater() {
        assertThat(hourInUtc(optimizer(true, 10).optimize(FUTURE_FRIDAY, "UTC", "credit"))).isEqualTo(11);
    }

    @Test
    void prepaidBehavesLikeDebit() {
        assertThat(hourInUtc(optimizer(true, 10).optimize(FUTURE_FRIDAY, "UTC", "prepaid"))).isEqualTo(8);
    }

    @Test
    void unknownCardTypeUsesBaseHour() {
        assertThat(hourInUtc(optimizer(true, 10).optimize(FUTURE_FRIDAY, "UTC", "unknown"))).isEqualTo(10);
    }

    @Test
    void nullCardTypeUsesBaseHour() {
        assertThat(hourInUtc(optimizer(true, 10).optimize(FUTURE_FRIDAY, "UTC", null))).isEqualTo(10);
    }

    @Test
    void cardTypeIsCaseInsensitive() {
        assertThat(hourInUtc(optimizer(true, 10).optimize(FUTURE_FRIDAY, "UTC", "DEBIT"))).isEqualTo(8);
    }

    @Test
    void debitClampsToFloorOfSix() {
        // optimalHour 7 - 2 = 5, clamped up to max(6, 5) = 6.
        assertThat(hourInUtc(optimizer(true, 7).optimize(FUTURE_FRIDAY, "UTC", "debit"))).isEqualTo(6);
    }

    @Test
    void creditClampsToCeilingOfEighteen() {
        // optimalHour 18 + 1 = 19, clamped down to min(18, 19) = 18.
        assertThat(hourInUtc(optimizer(true, 18).optimize(FUTURE_FRIDAY, "UTC", "credit"))).isEqualTo(18);
    }

    // ---- timezone resolution / fallback ----

    @Test
    void validCustomerTimezoneIsHonored() {
        // optimalHour 10 in New York. The wall-clock hour in that zone is 10.
        SmartRetryOptimizer opt = optimizer(true, 10);
        Instant result = opt.optimize(FUTURE_FRIDAY, "America/New_York", null);
        assertThat(result.atZone(ZoneId.of("America/New_York")).getHour()).isEqualTo(10);
    }

    @Test
    void invalidTimezoneFallsBackToNewYorkWithoutThrowing() {
        SmartRetryOptimizer opt = optimizer(true, 10);
        Instant result = opt.optimize(FUTURE_FRIDAY, "Not/AZone", null);
        // Falls back to America/New_York: hour 10 reads as 10 there, not in UTC.
        assertThat(result.atZone(ZoneId.of("America/New_York")).getHour()).isEqualTo(10);
    }

    @Test
    void blankTimezoneFallsBackToNewYork() {
        SmartRetryOptimizer opt = optimizer(true, 10);
        Instant result = opt.optimize(FUTURE_FRIDAY, "   ", null);
        assertThat(result.atZone(ZoneId.of("America/New_York")).getHour()).isEqualTo(10);
    }

    @Test
    void nullTimezoneFallsBackToNewYork() {
        SmartRetryOptimizer opt = optimizer(true, 10);
        Instant result = opt.optimize(FUTURE_FRIDAY, null, null);
        assertThat(result.atZone(ZoneId.of("America/New_York")).getHour()).isEqualTo(10);
    }

    // ---- weekend avoidance ----

    @Test
    void saturdayShiftsToMonday() {
        SmartRetryOptimizer opt = optimizer(true, 10);
        Instant result = opt.optimize(FUTURE_SATURDAY, "UTC", null);
        assertThat(result.atZone(UTC).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    void sundayShiftsToMonday() {
        SmartRetryOptimizer opt = optimizer(true, 10);
        Instant result = opt.optimize(FUTURE_SUNDAY, "UTC", null);
        assertThat(result.atZone(UTC).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    void weekdayIsNotShifted() {
        SmartRetryOptimizer opt = optimizer(true, 10);
        Instant result = opt.optimize(FUTURE_FRIDAY, "UTC", null);
        assertThat(result.atZone(UTC).getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
    }

    // ---- past-time guard ----

    @Test
    void pastScheduledTimeRollsForwardOneDayAndReappliesWeekendAvoidance() {
        // Base 2020-06-15 (Monday) in UTC at hour 10 is far in the past, so the
        // computed instant < now() -> guard adds one day -> 2020-06-16 (Tuesday,
        // still a weekday) at hour 10. Deterministic regardless of the actual clock.
        SmartRetryOptimizer opt = optimizer(true, 10);
        Instant past = Instant.parse("2020-06-15T03:00:00Z");
        Instant result = opt.optimize(past, "UTC", null);

        ZonedDateTime z = result.atZone(UTC);
        assertThat(z.toLocalDate()).isEqualTo(java.time.LocalDate.parse("2020-06-16"));
        assertThat(z.getHour()).isEqualTo(10);
        assertThat(z.getDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
    }
}
