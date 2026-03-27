package io.nexuspay.marketplace.adapter.in.rest.dto;

import java.math.BigDecimal;

/**
 * @since 0.4.1 (Sprint 4.2)
 */
public record UpdateAccountRequest(
        String businessName,
        String email,
        String payoutSchedule,
        long payoutMinimum,
        BigDecimal platformFeePercent,
        long platformFeeFixed
) {}
