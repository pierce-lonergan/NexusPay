package io.nexuspay.payment.application.screening;

import java.util.Map;

/**
 * How a payment flow is screened by the {@link PreAuthorizationGate} / GatedPaymentGateway
 * (B-024). Classified off request metadata (no SecurityContext), because the gate now sits
 * at the {@code PaymentGatewayPort} boundary and runs for background threads too.
 *
 * <p>Sanctions are a hard block in EVERY mode (OFAC applies to standing mandates too). The
 * modes differ only in how a fraud BLOCK is handled:</p>
 * <ul>
 *   <li>{@link #INTERACTIVE} — customer/operator-initiated: fraud BLOCK rejects the payment.
 *   <li>{@link #SERVER_RECURRING} — billing/subscription/dunning charges: fraud BLOCK is
 *       DOWNGRADED to REVIEW (authorize + hold capture + flag), never a hard decline, so a
 *       velocity rule does not silently churn a legitimate recurring mandate.
 *   <li>{@link #SERVER_OTHER} — other server-initiated (e.g. workflow activities): same
 *       downgrade as recurring.
 * </ul>
 */
public enum ScreeningMode {
    INTERACTIVE,
    SERVER_RECURRING,
    SERVER_OTHER;

    /** True when a fraud BLOCK should be softened to REVIEW (server-initiated rails). */
    public boolean downgradesBlockToReview() {
        return this != INTERACTIVE;
    }

    /**
     * Classify from request metadata. Billing stamps {@code source=billing_*}; the workflow
     * rail stamps {@code workflow=*}. Everything else (the interactive create/checkout path)
     * is INTERACTIVE.
     *
     * @deprecated CLIENT-DERIVED — do not use for new callers (B-029). The screening rail is now
     *     taken from a TRUSTED {@link CallContext} at every ingress, not from client-shaped request
     *     metadata (which a forwarding caller could set to claim the softer SERVER_* rail and dodge
     *     the interactive hold-capture). Retained ONLY for the transitional 1-arg
     *     {@code GatedPaymentGateway.createPayment(PaymentRequest)} fallback, which logs loudly.
     */
    @Deprecated
    public static ScreeningMode fromMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return INTERACTIVE;
        }
        Object source = metadata.get("source");
        if (source != null && source.toString().startsWith("billing")) {
            return SERVER_RECURRING;
        }
        if (metadata.containsKey("workflow")) {
            return SERVER_OTHER;
        }
        return INTERACTIVE;
    }
}
