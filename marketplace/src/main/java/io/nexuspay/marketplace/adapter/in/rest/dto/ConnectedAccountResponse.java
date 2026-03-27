package io.nexuspay.marketplace.adapter.in.rest.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @since 0.4.1 (Sprint 4.2)
 */
public record ConnectedAccountResponse(
        String accountId,
        String businessName,
        String email,
        String status,
        String kycStatus,
        String country,
        String defaultCurrency,
        String payoutSchedule,
        long payoutMinimum,
        BigDecimal platformFeePercent,
        long platformFeeFixed,
        Instant createdAt,
        Instant updatedAt
) {}
