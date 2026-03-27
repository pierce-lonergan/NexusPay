package io.nexuspay.vault.adapter.in.rest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for vaulting a card.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public record VaultCardRequest(
        @NotBlank String pan,
        @Min(1) @Max(12) int expMonth,
        @Min(2024) int expYear,
        String cardholderName
) {}
