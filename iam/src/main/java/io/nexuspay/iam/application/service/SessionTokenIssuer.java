package io.nexuspay.iam.application.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.nexuspay.common.domain.SessionToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates restricted-scope JWT tokens for payment sessions.
 *
 * <p>Session tokens use HMAC-SHA256 signing and contain claims that restrict
 * the token to a single payment session. The token can only be used for
 * checkout operations (tokenize, confirm) on the associated session.
 *
 * <p>Claims: {@code sub} = session ID, {@code tenant_id}, {@code scope} = "session:confirm",
 * {@code session_id}, {@code exp} = configurable (default 30 min).
 *
 * @since 0.3.5 (Sprint 3.5)
 */
@Service
public class SessionTokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(SessionTokenIssuer.class);
    private static final String SCOPE_CLAIM = "scope";
    private static final String TENANT_ID_CLAIM = "tenant_id";
    private static final String SESSION_ID_CLAIM = "session_id";
    private static final String SESSION_SCOPE = "session:confirm";
    /**
     * INT-3: signed mode claim — the SERVER-DERIVED {@code is_live} of the API key that created the
     * session. The SDK checkout filter re-derives the request's {@code PaymentMode} from this claim so a
     * test-mode session can NEVER move real money. Tamper-proof: it is inside the HMAC-signed payload.
     */
    private static final String LIVEMODE_CLAIM = "livemode";

    private final SecretKey signingKey;
    private final Duration defaultExpiry;

    public SessionTokenIssuer(
            @Value("${nexuspay.session.jwt-secret:dev-session-secret-minimum-32-chars!!}") String jwtSecret,
            @Value("${nexuspay.session.default-expiry:PT30M}") Duration defaultExpiry) {
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.defaultExpiry = defaultExpiry;
        log.info("SessionTokenIssuer initialized: defaultExpiry={}", defaultExpiry);
    }

    /**
     * Creates a restricted-scope JWT for the given payment session.
     *
     * <p>INT-3: {@code live} defaults to {@code true} (the safe direction for an un-migrated caller — a
     * stale TEST mode would route a real request to the mock = delivery gap, never a real charge). The
     * {@link io.nexuspay.gateway.application.service.PaymentSessionService session create path} MUST use
     * the {@code live}-carrying overload so the API key's real {@code is_live} is threaded through.</p>
     *
     * @param sessionId the payment session ID
     * @param tenantId  the tenant ID
     * @return the session token containing the signed JWT
     */
    public SessionToken issueToken(String sessionId, String tenantId) {
        return issueToken(sessionId, tenantId, true, defaultExpiry);
    }

    /**
     * Creates a restricted-scope JWT carrying the SERVER-DERIVED mode of the originating API key.
     *
     * @param sessionId the payment session ID
     * @param tenantId  the tenant ID
     * @param live      the originating API key's {@code is_live} (false for an {@code sk_test_} key)
     * @return the session token containing the signed JWT
     */
    public SessionToken issueToken(String sessionId, String tenantId, boolean live) {
        return issueToken(sessionId, tenantId, live, defaultExpiry);
    }

    /**
     * Creates a restricted-scope JWT with a custom expiry, carrying the originating key's mode.
     *
     * @param sessionId the payment session ID
     * @param tenantId  the tenant ID
     * @param live      the originating API key's {@code is_live} (false for an {@code sk_test_} key)
     * @param expiry    how long the token is valid
     * @return the session token containing the signed JWT
     */
    public SessionToken issueToken(String sessionId, String tenantId, boolean live, Duration expiry) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expiry);

        String jwt = Jwts.builder()
                .subject(sessionId)
                .claim(TENANT_ID_CLAIM, tenantId)
                .claim(SESSION_ID_CLAIM, sessionId)
                .claim(SCOPE_CLAIM, SESSION_SCOPE)
                .claim(LIVEMODE_CLAIM, live)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();

        log.debug("Issued session token for session={}, tenant={}, live={}, expiresAt={}",
                sessionId, tenantId, live, expiresAt);

        return new SessionToken(jwt, sessionId, tenantId, expiresAt, live);
    }

    /**
     * Validates a session token JWT and extracts the session claims.
     *
     * @param token the JWT string
     * @return the validated session token, or {@code null} if invalid
     */
    public SessionToken validateSessionToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String scope = claims.get(SCOPE_CLAIM, String.class);
            if (!SESSION_SCOPE.equals(scope)) {
                log.warn("Session token has invalid scope: {}", scope);
                return null;
            }

            String sessionId = claims.get(SESSION_ID_CLAIM, String.class);
            String tenantId = claims.get(TENANT_ID_CLAIM, String.class);
            Instant expiresAt = claims.getExpiration().toInstant();
            // INT-3: re-derive the request mode from the signed claim. A token MISSING the claim (issued
            // before INT-3) FAILS CLOSED to TEST (live=false) -> SDK checkout routes to the mock, never a
            // real charge. Session tokens are short-lived (≈30 min) so no meaningful long-lived live token
            // is mis-routed; an absent claim is treated as the safe direction.
            Boolean live = claims.get(LIVEMODE_CLAIM, Boolean.class);
            boolean liveMode = Boolean.TRUE.equals(live);

            return new SessionToken(token, sessionId, tenantId, expiresAt, liveMode);
        } catch (JwtException e) {
            log.debug("Session token validation failed: {}", e.getMessage());
            return null;
        }
    }
}
