package io.nexuspay.common.mode;

/**
 * INT-3: request-scoped holder for the authenticated caller's API-key mode.
 *
 * <p>TEST  = an {@code sk_test_} key ({@code is_live=false}) — payment operations route to the
 * in-memory {@code MockPaymentGatewayPort}, NEVER the real HyperSwitch adapter.<br>
 * LIVE  = an {@code sk_live_} key, or a JWT/OIDC console actor, or a system/scheduler/Kafka thread —
 * payment operations route to the real HyperSwitch adapter.</p>
 *
 * <p><b>The fail-closed direction differs by execution context, so it is resolved by the gateway, not
 * here</b> (this class only records the explicit/unset tri-state):
 * <ul>
 *   <li><b>REQUEST context</b> (a holder MAY have been set by the auth filter): if the mode cannot be
 *       affirmatively determined for a payment op, the gateway treats it as TEST — never risk a real
 *       charge on an unresolved request.</li>
 *   <li><b>SYSTEM context</b> (Kafka consumers, scheduled jobs, the OutboxRelay): the holder is NEVER
 *       set on these threads, so the gateway resolves LIVE — system threads are real, never test.</li>
 * </ul>
 * The gateway distinguishes the two via a servlet-request check; this holder simply reports
 * {@link #isUnset()} on any thread the auth filters never touched (which includes every async
 * consumer/scheduler thread), so async work defaults to LIVE.</p>
 *
 * <p>Mirrors {@code io.nexuspay.iam.domain.TenantContext}: a {@code ThreadLocal} set at request entry
 * and CLEARED in a {@code finally} block so it never leaks onto a pooled (platform or virtual) thread.
 * Virtual-thread safe — each (virtual) thread has its own ThreadLocal storage.</p>
 */
public final class PaymentMode {

    private static final ThreadLocal<Boolean> LIVE = new ThreadLocal<>();

    private PaymentMode() {
        // Utility class — state lives in the ThreadLocal.
    }

    /**
     * Records the caller's mode on the current thread. {@code live} is SERVER-DERIVED from the
     * authenticated key's {@code is_live} (or {@code true} for a non-API-key principal); it is never
     * parsed from the raw key string.
     */
    public static void set(boolean live) {
        LIVE.set(live);
    }

    /** True only when a request affirmatively resolved a TEST ({@code is_live=false}) key. */
    public static boolean isTestExplicit() {
        return Boolean.FALSE.equals(LIVE.get());
    }

    /** True only when a request affirmatively resolved a LIVE ({@code is_live=true}) key/principal. */
    public static boolean isLiveExplicit() {
        return Boolean.TRUE.equals(LIVE.get());
    }

    /**
     * True when no mode was set on this thread — i.e. a system/consumer/scheduler thread (the auth
     * filters never ran), or a servlet request that reached a payment op without ever authenticating.
     */
    public static boolean isUnset() {
        return LIVE.get() == null;
    }

    /**
     * Clears the mode for the current thread. MUST be called in a {@code finally} block to prevent a
     * ThreadLocal leak onto the next request served by a pooled thread.
     */
    public static void clear() {
        LIVE.remove();
    }
}
