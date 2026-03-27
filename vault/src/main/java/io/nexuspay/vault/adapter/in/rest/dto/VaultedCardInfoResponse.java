package io.nexuspay.vault.adapter.in.rest.dto;

import java.time.Instant;

/**
 * Response DTO for retrieving vaulted card metadata.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public record VaultedCardInfoResponse(
        String token,
        String panLast4,
        String panBin,
        String brand,
        int expMonth,
        int expYear,
        String cardholderName,
        Instant createdAt
) {}
