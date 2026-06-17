package io.nexuspay.billing.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Domain tests for Subscription — state machine + the calendar-aware billing
 * period math (calendar months/years, not fixed 30/365 days, with day-of-month
 * clamping). The renew() path exercises the otherwise-private period calculation.
 */
class SubscriptionTest {

    private Price price(String interval, int count, int trialDays) {
        Price p = new Price();
        p.setId("price_1");
        p.setBillingInterval(interval);
        p.setBillingIntervalCount(count);
        p.setTrialDays(trialDays);
        return p;
    }

    private Subscription activeWithPeriodEnd(Instant end) {
        Subscription s = new Subscription();
        s.setStatus(SubscriptionState.ACTIVE);
        s.setCancelAtPeriodEnd(false);
        s.setCurrentPeriodEnd(end);
        return s;
    }

    // ---- creation ----

    @Test
    void createWithTrialStartsTrialing() {
        Subscription s = Subscription.create("t1", "cust1", price("MONTH", 1, 14), 1, "pm_1", Map.of(), true);
        assertThat(s.getStatus()).isEqualTo(SubscriptionState.TRIALING);
        assertThat(s.getTrialEnd()).isNotNull();
        assertThat(s.getId()).startsWith("sub_");
    }

    @Test
    void createWithoutTrialStartsActive() {
        Subscription s = Subscription.create("t1", "cust1", price("MONTH", 1, 0), 1, "pm_1", Map.of(), true);
        assertThat(s.getStatus()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(s.getCurrentPeriodEnd()).isAfter(s.getCurrentPeriodStart());
    }

    // ---- DX-5a: durable test/live mode is stamped at creation ----

    @Test
    void createStampsDurableTestLiveModeFromCaller() {
        // A test-key create marks is_live=false so the subscription's future SYSTEM-thread
        // renewal/dunning charges route to the mock, never the real PSP.
        Subscription test = Subscription.create("t1", "cust1", price("MONTH", 1, 0), 1, "pm_1", Map.of(), false);
        assertThat(test.isLive()).isFalse();

        Subscription live = Subscription.create("t1", "cust1", price("MONTH", 1, 0), 1, "pm_1", Map.of(), true);
        assertThat(live.isLive()).isTrue();
    }

    @Test
    void newSubscriptionDefaultsToLiveBeforeStamping() {
        // The no-arg constructor (used by the JPA mapper before setLive runs / a pre-V4035 row) defaults
        // to LIVE — the safe-for-existing-prod default.
        assertThat(new Subscription().isLive()).isTrue();
    }

    // ---- calendar period math (the fix) ----

    @Test
    void monthlyRenewalUsesCalendarMonthAndClampsDayOfMonth() {
        // Jan 31 + 1 month must be Feb 28 (2026 not a leap year), NOT Mar 2 (a 30-day drift).
        Subscription s = activeWithPeriodEnd(Instant.parse("2026-01-31T00:00:00Z"));
        s.renew(price("MONTH", 1, 0));
        assertThat(s.getCurrentPeriodStart()).isEqualTo(Instant.parse("2026-01-31T00:00:00Z"));
        assertThat(s.getCurrentPeriodEnd()).isEqualTo(Instant.parse("2026-02-28T00:00:00Z"));
    }

    @Test
    void yearlyRenewalHandlesLeapDayClamping() {
        // Feb 29 2024 + 1 year -> Feb 28 2025 (clamped), not Feb 28 + 365 drift.
        Subscription s = activeWithPeriodEnd(Instant.parse("2024-02-29T00:00:00Z"));
        s.renew(price("YEAR", 1, 0));
        assertThat(s.getCurrentPeriodEnd()).isEqualTo(Instant.parse("2025-02-28T00:00:00Z"));
    }

    @Test
    void weeklyAndDailyAreFixedDayMultiples() {
        Subscription wk = activeWithPeriodEnd(Instant.parse("2026-01-01T00:00:00Z"));
        wk.renew(price("WEEK", 2, 0));
        assertThat(wk.getCurrentPeriodEnd()).isEqualTo(Instant.parse("2026-01-15T00:00:00Z"));

        Subscription day = activeWithPeriodEnd(Instant.parse("2026-01-01T00:00:00Z"));
        day.renew(price("DAY", 10, 0));
        assertThat(day.getCurrentPeriodEnd()).isEqualTo(Instant.parse("2026-01-11T00:00:00Z"));
    }

    @Test
    void renewWhenCancelAtPeriodEndCancels() {
        Subscription s = activeWithPeriodEnd(Instant.parse("2026-01-01T00:00:00Z"));
        s.setCancelAtPeriodEnd(true);
        s.renew(price("MONTH", 1, 0));
        assertThat(s.getStatus()).isEqualTo(SubscriptionState.CANCELED);
        assertThat(s.getCanceledAt()).isNotNull();
    }

    // ---- state machine guards ----

    @Test
    void pauseRequiresActive() {
        Subscription s = activeWithPeriodEnd(Instant.parse("2026-01-01T00:00:00Z"));
        s.pause();
        assertThat(s.getStatus()).isEqualTo(SubscriptionState.PAUSED);
        assertThatThrownBy(() -> s.pause()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resumeRequiresPaused() {
        Subscription s = activeWithPeriodEnd(Instant.parse("2026-01-01T00:00:00Z"));
        assertThatThrownBy(() -> s.resume(price("MONTH", 1, 0))).isInstanceOf(IllegalStateException.class);
        s.pause();
        s.resume(price("MONTH", 1, 0));
        assertThat(s.getStatus()).isEqualTo(SubscriptionState.ACTIVE);
    }

    @Test
    void markPastDueOnlyFromActive() {
        Subscription s = activeWithPeriodEnd(Instant.parse("2026-01-01T00:00:00Z"));
        s.markPastDue();
        assertThat(s.getStatus()).isEqualTo(SubscriptionState.PAST_DUE);
    }

    @Test
    void renewalDueWhenPeriodEnded() {
        Subscription past = activeWithPeriodEnd(Instant.now().minusSeconds(3600));
        assertThat(past.isRenewalDue()).isTrue();
        Subscription future = activeWithPeriodEnd(Instant.now().plusSeconds(3600));
        assertThat(future.isRenewalDue()).isFalse();
    }
}
