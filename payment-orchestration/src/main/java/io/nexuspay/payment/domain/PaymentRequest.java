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
}
