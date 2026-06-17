package io.nexuspay.billing.domain;

import io.nexuspay.common.id.PrefixedId;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Aggregate root for a subscription billing lifecycle.
 *
 * <p>Manages state transitions: TRIALING → ACTIVE → PAST_DUE → CANCELED.
 * Supports pause/resume, cancel-at-period-end, and quantity changes.</p>
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
public class Subscription {

    private String id;
    private String tenantId;
    private String customerId;
    private String priceId;
    private SubscriptionState status;
    private int quantity;
    private Instant currentPeriodStart;
    private Instant currentPeriodEnd;
    private Instant trialStart;
    private Instant trialEnd;
    private Instant canceledAt;
    private boolean cancelAtPeriodEnd;
    private String paymentMethodId;
    private Map<String, Object> metadata;
    /**
     * DX-5a (MONEY-SAFETY): the DURABLE test/live mode of this subscription, mapped to
     * {@code subscriptions.is_live}. Stamped at creation from the creating caller's server-derived
     * key mode (a test key ⇒ {@code false}, a live/console key ⇒ {@code true}); it is the same
     * {@code is_live} that mints {@code sk_test_}/{@code sk_live_}. The renewal/dunning schedulers
     * run on a SYSTEM thread where the request-scoped {@code PaymentMode} is gone, so they thread
     * this durable flag into the gateway {@code CallContext} to route a TEST subscription's
     * recurring charge to the mock — never the real PSP.
     *
     * <p>Defaults {@code true} (LIVE) so an entity hydrated from a pre-V4035 row — or constructed via
     * the no-arg constructor + setters by the JPA mapper before {@code setLive} runs — is treated as
     * LIVE (the safe-for-existing-prod default; a backfilled/unknown row must not silently become
     * test and stop collecting real money).</p>
     */
    private boolean live = true;
    private Instant createdAt;
    private Instant updatedAt;

    public Subscription() {
    }

    /**
     * Creates a new subscription. If the price has trial days, starts in TRIALING state.
     *
     * @param isLive DX-5a: the DURABLE test/live mode, server-derived from the creating caller's key
     *               mode (a test key ⇒ {@code false}, a live/console key ⇒ {@code true}). Persisted to
     *               {@code is_live} so the renewal/dunning schedulers route a TEST subscription's
     *               recurring charge to the mock — never the real PSP.
     */
    public static Subscription create(String tenantId, String customerId, Price price,
                                       int quantity, String paymentMethodId,
                                       Map<String, Object> metadata, boolean isLive) {
        Subscription s = new Subscription();
        s.id = PrefixedId.subscription();
        s.tenantId = tenantId;
        s.customerId = customerId;
        s.priceId = price.getId();
        s.quantity = quantity;
        s.paymentMethodId = paymentMethodId;
        s.metadata = metadata;
        s.live = isLive;
        s.createdAt = Instant.now();
        s.updatedAt = s.createdAt;

        if (price.getTrialDays() > 0) {
            s.status = SubscriptionState.TRIALING;
            s.trialStart = s.createdAt;
            s.trialEnd = s.createdAt.plus(price.getTrialDays(), ChronoUnit.DAYS);
            s.currentPeriodStart = s.trialStart;
            s.currentPeriodEnd = s.trialEnd;
        } else {
            s.status = SubscriptionState.ACTIVE;
            s.currentPeriodStart = s.createdAt;
            s.currentPeriodEnd = calculatePeriodEnd(s.createdAt, price);
        }
        return s;
    }

    // -- State transitions --

    /**
     * Activates the subscription (from trial or past-due recovery).
     */
    public void activate(Price price) {
        if (status != SubscriptionState.TRIALING && status != SubscriptionState.PAST_DUE) {
            throw new IllegalStateException("Cannot activate from state " + status);
        }
        this.status = SubscriptionState.ACTIVE;
        if (this.trialEnd != null && Instant.now().isAfter(this.trialEnd)) {
            // Trial ended — start first real billing period
            this.currentPeriodStart = Instant.now();
            this.currentPeriodEnd = calculatePeriodEnd(this.currentPeriodStart, price);
        }
        this.updatedAt = Instant.now();
    }

    /**
     * Marks as past due after a payment failure.
     */
    public void markPastDue() {
        if (status != SubscriptionState.ACTIVE) {
            throw new IllegalStateException("Cannot mark past-due from state " + status);
        }
        this.status = SubscriptionState.PAST_DUE;
        this.updatedAt = Instant.now();
    }

    /**
     * Cancel immediately or at period end.
     */
    public void cancel(boolean atPeriodEnd) {
        if (status.isTerminal()) {
            throw new IllegalStateException("Subscription already in terminal state " + status);
        }
        if (atPeriodEnd) {
            this.cancelAtPeriodEnd = true;
        } else {
            this.status = SubscriptionState.CANCELED;
            this.canceledAt = Instant.now();
        }
        this.updatedAt = Instant.now();
    }

    /**
     * Pause the subscription (admin action).
     */
    public void pause() {
        if (status != SubscriptionState.ACTIVE) {
            throw new IllegalStateException("Can only pause ACTIVE subscriptions, was " + status);
        }
        this.status = SubscriptionState.PAUSED;
        this.updatedAt = Instant.now();
    }

    /**
     * Resume a paused subscription.
     */
    public void resume(Price price) {
        if (status != SubscriptionState.PAUSED) {
            throw new IllegalStateException("Can only resume PAUSED subscriptions, was " + status);
        }
        this.status = SubscriptionState.ACTIVE;
        this.currentPeriodStart = Instant.now();
        this.currentPeriodEnd = calculatePeriodEnd(this.currentPeriodStart, price);
        this.updatedAt = Instant.now();
    }

    /**
     * Advances to the next billing period after successful renewal.
     */
    public void renew(Price price) {
        if (this.cancelAtPeriodEnd) {
            this.status = SubscriptionState.CANCELED;
            this.canceledAt = Instant.now();
            this.updatedAt = Instant.now();
            return;
        }
        this.currentPeriodStart = this.currentPeriodEnd;
        this.currentPeriodEnd = calculatePeriodEnd(this.currentPeriodStart, price);
        this.updatedAt = Instant.now();
    }

    /**
     * Dunning exhausted — cancel the subscription.
     */
    public void cancelDueToNonPayment() {
        this.status = SubscriptionState.CANCELED;
        this.canceledAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // -- Helpers --

    public boolean isTrialExpired() {
        return status == SubscriptionState.TRIALING
                && trialEnd != null
                && Instant.now().isAfter(trialEnd);
    }

    public boolean isRenewalDue() {
        return (status == SubscriptionState.ACTIVE || status == SubscriptionState.PAST_DUE)
                && currentPeriodEnd != null
                && Instant.now().isAfter(currentPeriodEnd);
    }

    private static Instant calculatePeriodEnd(Instant start, Price price) {
        int count = price.getBillingIntervalCount();
        // MONTH/YEAR use calendar arithmetic (not fixed 30/365 days): a flat day
        // count makes a "monthly" plan drift forward ~5-6 days/year and never
        // land on a stable day-of-month, and misses leap years for "yearly".
        return switch (price.getBillingInterval().toUpperCase()) {
            case "DAY" -> start.plus(count, ChronoUnit.DAYS);
            case "WEEK" -> start.plus((long) count * 7, ChronoUnit.DAYS);
            case "MONTH" -> plusCalendar(start, Period.ofMonths(count));
            case "YEAR" -> plusCalendar(start, Period.ofYears(count));
            default -> plusCalendar(start, Period.ofMonths(1));
        };
    }

    /** Adds a calendar period in UTC, preserving day-of-month with clamping. */
    private static Instant plusCalendar(Instant start, Period period) {
        return start.atZone(ZoneOffset.UTC).plus(period).toInstant();
    }

    // -- Getters & setters --

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getPriceId() { return priceId; }
    public void setPriceId(String priceId) { this.priceId = priceId; }
    public SubscriptionState getStatus() { return status; }
    public void setStatus(SubscriptionState status) { this.status = status; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public Instant getCurrentPeriodStart() { return currentPeriodStart; }
    public void setCurrentPeriodStart(Instant s) { this.currentPeriodStart = s; }
    public Instant getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(Instant e) { this.currentPeriodEnd = e; }
    public Instant getTrialStart() { return trialStart; }
    public void setTrialStart(Instant trialStart) { this.trialStart = trialStart; }
    public Instant getTrialEnd() { return trialEnd; }
    public void setTrialEnd(Instant trialEnd) { this.trialEnd = trialEnd; }
    public Instant getCanceledAt() { return canceledAt; }
    public void setCanceledAt(Instant canceledAt) { this.canceledAt = canceledAt; }
    public boolean isCancelAtPeriodEnd() { return cancelAtPeriodEnd; }
    public void setCancelAtPeriodEnd(boolean cancelAtPeriodEnd) { this.cancelAtPeriodEnd = cancelAtPeriodEnd; }
    public String getPaymentMethodId() { return paymentMethodId; }
    public void setPaymentMethodId(String id) { this.paymentMethodId = id; }
    /** DX-5a: durable test/live mode (true=live, false=test); see {@link #create}. */
    public boolean isLive() { return live; }
    public void setLive(boolean live) { this.live = live; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
