package io.nexuspay.gateway.domain;

import java.time.Instant;

/**
 * A restricted-scope JWT token issued for a specific payment session.
 * The token allows the SDK to perform only session-related operations
 * (tokenize, confirm) and cannot be used for any other API calls.
 *
 * @param token     the signed JWT string
 * @param sessionId the payment session this token is scoped to
 * @param tenantId  the tenant this token belongs to
 * @param expiresAt when this token expires
 * @since 0.3.5 (Sprint 3.5)
 */
public record SessionToken(
        String token,
        String sessionId,
        String tenantId,
        Instant expiresAt
) {
}
