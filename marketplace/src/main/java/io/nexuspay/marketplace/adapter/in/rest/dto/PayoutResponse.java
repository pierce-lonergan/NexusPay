package io.nexuspay.marketplace.adapter.in.rest.dto;

import java.time.Instant;

/**
 * @since 0.4.1 (Sprint 4.2)
 */
public record PayoutResponse(
        String payoutId,
        String connectedAccountId,
        long amount,
        String currency,
        String status,
        String method,
        Instant scheduledAt,
        Instant paidAt,
        String failureReason,
        String externalReference,
        Instant createdAt
) {}
