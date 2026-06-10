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
     * @param sessionId the payment session ID
     * @param tenantId  the tenant ID
     * @return the session token containing the signed JWT
     */
    public SessionToken issueToken(String sessionId, String tenantId) {
        return issueToken(sessionId, tenantId, defaultExpiry);
    }

    /**
     * Creates a restricted-scope JWT with a custom expiry.
     *
     * @param sessionId the payment session ID
     * @param tenantId  the tenant ID
     * @param expiry    how long the token is valid
     * @return the session token containing the signed JWT
     */
    public SessionToken issueToken(String sessionId, String tenantId, Duration expiry) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expiry);

        String jwt = Jwts.builder()
                .subject(sessionId)
                .claim(TENANT_ID_CLAIM, tenantId)
                .claim(SESSION_ID_CLAIM, sessionId)
                .claim(SCOPE_CLAIM, SESSION_SCOPE)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();

        log.debug("Issued session token for session={}, tenant={}, expiresAt={}",
                sessionId, tenantId, expiresAt);

        return new SessionToken(jwt, sessionId, tenantId, expiresAt);
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

            return new SessionToken(token, sessionId, tenantId, expiresAt);
        } catch (JwtException e) {
            log.debug("Session token validation failed: {}", e.getMessage());
            return null;
        }
    }
}
