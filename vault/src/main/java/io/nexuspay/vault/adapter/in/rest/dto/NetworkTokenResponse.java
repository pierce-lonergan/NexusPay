package io.nexuspay.vault.adapter.in.rest.dto;

/**
 * Response DTO for a provisioned network token.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public record NetworkTokenResponse(
        String networkTokenId,
        String tokenLast4,
        String status,
        String network
) {}
