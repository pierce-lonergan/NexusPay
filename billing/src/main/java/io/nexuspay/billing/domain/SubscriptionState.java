package io.nexuspay.billing.domain;

/**
 * Subscription lifecycle states.
 *
 * <pre>
 *              ┌──────────────┐
 *              │   TRIALING   │
 *              └──────┬───────┘
 *                     │ trial_end reached
 *              ┌──────▼───────┐
 *        ┌─────│    ACTIVE    │◄──── payment succeeds (from PAST_DUE)
 *        │     └──────┬───────┘
 *        │            │ payment fails
 *        │     ┌──────▼───────┐
 *        │     │   PAST_DUE   │──── dunning exhausted → CANCELED
 *        │     └──────────────┘
 *        │
 *        │ cancel
 *        │     ┌──────────────┐
 *        └────►│   CANCELED   │
 *              └──────────────┘
 *
 *  PAUSED ◄──► ACTIVE (admin action)
 *  EXPIRED — subscription term ended naturally
 * </pre>
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
public enum SubscriptionState {

    /** Trial period active — no charges yet. */
    TRIALING,

    /** Active and billing normally. */
    ACTIVE,

    /** Payment failed — dunning in progress. */
    PAST_DUE,

    /** Temporarily paused by admin or customer. */
    PAUSED,

    /** Canceled (by customer, admin, or dunning exhaustion). */
    CANCELED,

    /** Natural term expiration. */
    EXPIRED;

    /** Returns {@code true} if this state is terminal (no further transitions). */
    public boolean isTerminal() {
        return this == CANCELED || this == EXPIRED;
    }

    /** Returns {@code true} if the subscription is currently billable. */
    public boolean isBillable() {
        return this == ACTIVE || this == PAST_DUE;
    }
}
