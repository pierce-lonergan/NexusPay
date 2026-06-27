package io.nexuspay.payment.domain;

import java.util.Map;

/**
 * Domain request for creating a payment.
 * All amounts in minor currency units (cents for USD, yen for JPY).
 *
 * <p>TEST-3c adds four OPTIONAL off-session fields ({@code paymentMethod}, {@code offSession},
 * {@code setupFutureUsage}, {@code mandateId}). They are pure PSP-request hints (not persisted on the
 * payment entity) and are {@code null} for every existing inline-card create. A 10-arg compatibility
 * constructor keeps EVERY pre-3c call site source- and behaviour-identical (the new fields default to
 * {@code null}), so the inline-card path and the live HyperSwitch wire are byte-identical.</p>
 */
public record PaymentRequest(
        long amount,
        String currency,
        String customerId,
        String paymentMethodType,
        String paymentMethodData,
        String returnUrl,
        String description,
        String captureMethod,       // "automatic" or "manual"
        String idempotencyKey,
        Map<String, Object> metadata,
        // TEST-3c off-session fields — all OPTIONAL (null on the inline-card path). NOT serialized to any
        // wire from this domain record; they are mapped onto HsPaymentCreateRequest by the adapter.
        String paymentMethod,       // the resolved opaque credentialRef (the chargeable handle; NO PAN)
        Boolean offSession,         // nullable tri-state: null = omitted (inline-card byte-identical)
        String setupFutureUsage,    // e.g. "off_session" / "on_session"
        String mandateId            // 3d hint; threaded through, no mandate resource created in 3c
) {
    public PaymentRequest {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        if (currency == null || currency.isBlank()) throw new IllegalArgumentException("currency required");
    }

    /**
     * Backward-compatible 10-arg constructor: every pre-3c call site ({@code PaymentController} inline
     * path, {@code GatedPaymentGateway.scrubAuthorityMarkers}/{@code reconstructForScreening}, billing,
     * workflow, the SDK checkout path, and all existing tests) constructs the request WITHOUT the four
     * off-session fields, which default to {@code null}. This keeps those sites source-identical and the
     * produced request (and the live PSP wire) byte-identical.
     */
    public PaymentRequest(long amount, String currency, String customerId, String paymentMethodType,
                          String paymentMethodData, String returnUrl, String description,
                          String captureMethod, String idempotencyKey, Map<String, Object> metadata) {
        this(amount, currency, customerId, paymentMethodType, paymentMethodData, returnUrl, description,
                captureMethod, idempotencyKey, metadata, null, null, null, null);
    }

    /** Returns a copy with a different capture method (e.g. forced to "manual" when
     *  a fraud REVIEW holds capture — B-003). Copies the off-session fields unchanged. */
    public PaymentRequest withCaptureMethod(String newCaptureMethod) {
        return new PaymentRequest(amount, currency, customerId, paymentMethodType,
                paymentMethodData, returnUrl, description, newCaptureMethod, idempotencyKey, metadata,
                paymentMethod, offSession, setupFutureUsage, mandateId);
    }
}
