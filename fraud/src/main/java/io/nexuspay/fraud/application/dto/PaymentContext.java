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
        Map<String, Object> metadata
) {}
