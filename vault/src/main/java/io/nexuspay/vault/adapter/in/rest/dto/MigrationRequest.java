package io.nexuspay.vault.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Request DTO for starting a vault migration.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public record MigrationRequest(
        @NotBlank String sourceProvider,
        @Positive int totalCards
) {}
