package io.nexuspay.gateway.domain;

/**
 * Thrown when an operation is attempted on an expired or locked payment session.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
public class SessionExpiredException extends RuntimeException {

    private final String sessionId;

    public SessionExpiredException(String sessionId) {
        super("Payment session expired or locked: " + sessionId);
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
