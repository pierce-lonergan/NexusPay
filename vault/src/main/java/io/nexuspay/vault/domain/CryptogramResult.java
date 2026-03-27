package io.nexuspay.vault.domain;

import java.time.Instant;

/**
 * Result of a cryptogram generation, containing the TAVV/CAVV and ECI indicator.
 *
 * @since 0.4.0 (Sprint 4.1)
 */
public record CryptogramResult(
        String cryptogram,
        String eci,
        Instant expiresAt
) {}
