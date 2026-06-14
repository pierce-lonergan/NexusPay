package io.nexuspay.fraud.application.dto;

import java.util.Map;

/**
 * Context information about a payment being assessed for fraud.
 * Passed into the fraud evaluation pipeline.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public record PaymentContext(
        String paymentId,
        String tenantId,
        long amountMinorUnits,
        String currency,
        String customerId,
        String customerEmail,
        String cardBin,            // first 6-8 digits
        String cardHash,           // tokenized card identifier for velocity checks
        String ipAddress,
        String ipCountry,
        String deviceFingerprintHash,
        Map<String, String> deviceInfo,  // browser, os, screen, timezone, language
        Map<String, Object> metadata,
        // B-027b: the idempotency key for this logical request. assess() dedups on
        // (tenantId, idempotencyKey) so a network/billing/Temporal RETRY of the SAME request does
        // not re-run the pipeline → no double velocity increment, no duplicate assessment row/event.
        // On the gate path this equals paymentId (== the payment ref); kept explicit + self-
        // documenting. Falls back to paymentId when not separately supplied.
        String idempotencyKey
) {
    /** Effective dedup key: the explicit idempotency key when present, else the payment ref. */
    public String dedupKey() {
        return (idempotencyKey != null && !idempotencyKey.isBlank()) ? idempotencyKey : paymentId;
    }
}
