package io.nexuspay.payment.domain;

/**
 * Request to capture a previously authorized payment.
 * If amountToCapture is null, captures the full authorized amount.
 */
public record CaptureRequest(
        Long amountToCapture,
        String idempotencyKey
) {
}
