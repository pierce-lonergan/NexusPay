package io.nexuspay.vault.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for provisioning a network token.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public record NetworkTokenRequest(
        @NotBlank String network
) {}
