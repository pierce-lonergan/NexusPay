package io.nexuspay.vault.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Request DTO for generating a cryptogram.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public record CryptogramRequestDto(
        @NotBlank String networkTokenId,
        @Positive long amount,
        @NotBlank String currency,
        String merchantId
) {}
