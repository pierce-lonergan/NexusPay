package io.nexuspay.vault.adapter.in.rest.dto;

import java.time.Instant;

/**
 * Response DTO for a generated cryptogram.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public record CryptogramResponse(
        String cryptogram,
        String eci,
        Instant expiresAt
) {}
