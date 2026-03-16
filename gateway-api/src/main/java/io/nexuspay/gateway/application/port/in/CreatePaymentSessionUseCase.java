package io.nexuspay.gateway.application.port.in;

import io.nexuspay.gateway.domain.PaymentSession;
import io.nexuspay.gateway.domain.SessionToken;

import java.util.List;
import java.util.Map;

/**
 * Creates a client-side payment session with a restricted-scope JWT.
 * The session token can only be used to tokenize payment methods and
 * confirm the specific payment associated with this session.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
public interface CreatePaymentSessionUseCase {

    /**
     * Creates a new payment session and issues a session token.
     *
     * @param command the session creation parameters
     * @return the result containing the session and its JWT token
     */
    CreateSessionResult create(CreateSessionCommand command);

    /**
     * @param tenantId              the tenant creating the session
     * @param amount                payment amount in minor units (e.g., cents)
     * @param currency              three-letter ISO 4217 currency code
     * @param customerId            optional customer identifier
     * @param successUrl            URL to redirect on successful payment
     * @param cancelUrl             URL to redirect on cancelled payment
     * @param allowedPaymentMethods payment methods allowed (default: ["card"])
     * @param branding              merchant branding for hosted checkout (logo, colors)
     * @param metadata              arbitrary key-value metadata
     */
    record CreateSessionCommand(
            String tenantId,
            long amount,
            String currency,
            String customerId,
            String successUrl,
            String cancelUrl,
            List<String> allowedPaymentMethods,
            Map<String, Object> branding,
            Map<String, Object> metadata
    ) {
    }

    /**
     * @param session the created payment session
     * @param token   the restricted-scope JWT for SDK authentication
     */
    record CreateSessionResult(
            PaymentSession session,
            SessionToken token
    ) {
    }
}
