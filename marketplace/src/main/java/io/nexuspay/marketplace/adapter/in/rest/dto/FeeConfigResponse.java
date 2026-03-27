package io.nexuspay.marketplace.adapter.in.rest.dto;

import java.math.BigDecimal;

/**
 * @since 0.4.1 (Sprint 4.2)
 */
public record FeeConfigResponse(
        String connectedAccountId,
        BigDecimal feePercent,
        long feeFixed
) {}
