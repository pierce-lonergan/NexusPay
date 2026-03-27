package io.nexuspay.marketplace.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * @since 0.4.1 (Sprint 4.2)
 */
public record CreatePayoutRequest(
        @NotBlank String connectedAccountId,
        @Positive long amount,
        @NotBlank String currency,
        @NotBlank String method,
        Instant scheduledAt
) {}
