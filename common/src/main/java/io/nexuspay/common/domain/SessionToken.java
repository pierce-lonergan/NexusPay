package io.nexuspay.common.domain;

import java.time.Instant;

/**
 * A restricted-scope JWT token issued for a specific payment session.
 * The token allows the SDK to perform only session-related operations
 * (tokenize, confirm) and cannot be used for any other API calls.
 *
 * <p>Lives in {@code common} because it is shared by the {@code iam} module
 * (issuing/validating) and the {@code gateway} module (session endpoints) —
 * gateway already depends on iam, so placing it in either would create a
 * module cycle.</p>
 *
 * @param token     the signed JWT string
 * @param sessionId the payment session this token is scoped to
 * @param tenantId  the tenant this token belongs to
 * @param expiresAt when this token expires
 * @param live      INT-3: the SERVER-DERIVED mode of the API key that created the session
 *                  ({@code is_live}). Carried as a signed JWT claim so the
 *                  {@code SessionTokenAuthenticationFilter} can re-derive the request's
 *                  {@code PaymentMode} at SDK checkout time — a session created under an
 *                  {@code sk_test_} key ({@code live=false}) MUST route its checkout/confirm to
 *                  the mock gateway, never the real PSP.
 * @since 0.3.5 (Sprint 3.5)
 */
public record SessionToken(
        String token,
        String sessionId,
        String tenantId,
        Instant expiresAt,
        boolean live
) {
}
