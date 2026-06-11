package io.nexuspay.payment.domain;

import java.util.Map;

/**
 * Domain request for creating a payment.
 * All amounts in minor currency units (cents for USD, yen for JPY).
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
        Map<String, Object> metadata
) {
    public PaymentRequest {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        if (currency == null || currency.isBlank()) throw new IllegalArgumentException("currency required");
    }

    /** Returns a copy with a different capture method (e.g. forced to "manual" when
     *  a fraud REVIEW holds capture — B-003). */
    public PaymentRequest withCaptureMethod(String newCaptureMethod) {
        return new PaymentRequest(amount, currency, customerId, paymentMethodType,
                paymentMethodData, returnUrl, description, newCaptureMethod, idempotencyKey, metadata);
    }
}
