package io.nexuspay.vault.adapter.in.rest.dto;

/**
 * Response DTO after vaulting a card.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public record VaultCardResponse(
        String token,
        String panLast4,
        String brand,
        String fingerprint
) {}
