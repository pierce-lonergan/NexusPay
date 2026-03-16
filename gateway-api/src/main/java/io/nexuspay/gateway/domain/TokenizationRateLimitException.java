package io.nexuspay.gateway.domain;

/**
 * Thrown when the maximum number of tokenization attempts per session is exceeded.
 * The session is locked (expired) when this occurs to prevent brute-force card testing.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
public class TokenizationRateLimitException extends RuntimeException {

    private final String sessionId;
    private final int maxAttempts;

    public TokenizationRateLimitException(String sessionId, int maxAttempts) {
        super("Tokenization rate limit exceeded for session " + sessionId
                + " (max " + maxAttempts + " attempts)");
        this.sessionId = sessionId;
        this.maxAttempts = maxAttempts;
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }
}
