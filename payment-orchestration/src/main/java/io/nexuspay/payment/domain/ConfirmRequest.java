package io.nexuspay.payment.domain;

/**
 * Request to confirm/authorize a payment that is in requires_confirmation state.
 */
public record ConfirmRequest(
        String paymentMethodType,
        String paymentMethodData,
        String returnUrl,
        String idempotencyKey
) {
}
