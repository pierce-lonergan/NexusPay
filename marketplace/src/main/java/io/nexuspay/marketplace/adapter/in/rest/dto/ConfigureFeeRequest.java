package io.nexuspay.marketplace.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * @since 0.4.1 (Sprint 4.2)
 */
public record ConfigureFeeRequest(
        @NotBlank String connectedAccountId,
        @PositiveOrZero BigDecimal feePercent,
        @PositiveOrZero long feeFixed
) {}
