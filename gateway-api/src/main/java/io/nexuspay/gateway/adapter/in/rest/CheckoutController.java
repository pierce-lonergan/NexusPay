package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.domain.ApiError;
import io.nexuspay.common.domain.ApiErrorResponse;
import io.nexuspay.gateway.adapter.in.rest.dto.*;
import io.nexuspay.gateway.application.port.in.TokenizePaymentMethodUseCase;
import io.nexuspay.gateway.application.port.in.TokenizePaymentMethodUseCase.TokenizeCommand;
import io.nexuspay.gateway.application.port.out.PaymentTokenRepository;
import io.nexuspay.gateway.application.service.PaymentSessionService;
import io.nexuspay.gateway.domain.PaymentSession;
import io.nexuspay.gateway.domain.PaymentToken;
import io.nexuspay.gateway.domain.SessionExpiredException;
import io.nexuspay.gateway.domain.TokenizationRateLimitException;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.CallContext;
import io.nexuspay.payment.domain.PaymentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Client-side checkout API for the SDK.
 * Authenticated via session token JWT (not API key/Keycloak JWT).
 *
 * <p>All endpoints enforce session scoping: the authenticated principal's
 * {@code sessionId} must match the session being accessed.
 *
 * @since 0.3.5 (Sprint 3.5)
 */
@RestController
@RequestMapping("/v1/checkout")
@Tag(name = "Checkout", description = "Client-side SDK checkout operations")
public class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    /** MDC key the CorrelationIdFilter populates (same constant as gateway's CorrelationIdFilter). */
    private static final String MDC_REQUEST_ID = "request_id";

    private final PaymentSessionService sessionService;
    private final TokenizePaymentMethodUseCase tokenizeUseCase;
    private final PaymentTokenRepository paymentTokenRepository;
    private final PaymentGatewayPort paymentGateway;

    public CheckoutController(PaymentSessionService sessionService,
                              TokenizePaymentMethodUseCase tokenizeUseCase,
                              PaymentTokenRepository paymentTokenRepository,
                              PaymentGatewayPort paymentGateway) {
        this.sessionService = sessionService;
        this.tokenizeUseCase = tokenizeUseCase;
        this.paymentTokenRepository = paymentTokenRepository;
        this.paymentGateway = paymentGateway;
    }

    @GetMapping("/session")
    @Operation(summary = "Get current session status (SDK)")
    public ResponseEntity<SessionStatusResponse> getSessionStatus(
            @AuthenticationPrincipal NexusPayPrincipal principal) {

        return sessionService.findById(principal.sessionId())
                .map(session -> ResponseEntity.ok(ResponseMapper.toSessionStatusResponse(session)))
                .orElse(ResponseEntity.status(HttpStatus.GONE).build());
    }

    @PostMapping("/tokenize")
    @Operation(summary = "Tokenize a payment method (SDK)")
    public ResponseEntity<?> tokenize(
            @Valid @RequestBody TokenizeRequest request,
            @AuthenticationPrincipal NexusPayPrincipal principal) {

        try {
            byte[] tokenData = request.token_data() != null
                    ? request.token_data().getBytes(StandardCharsets.UTF_8)
                    : new byte[0];

            var token = tokenizeUseCase.tokenize(new TokenizeCommand(
                    principal.sessionId(), principal.tenantId(),
                    request.type(), tokenData,
                    request.card_last_four(), request.card_brand(),
                    request.card_exp_month(), request.card_exp_year()
            ));

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ResponseMapper.toTokenizeResponse(token));
        } catch (SessionExpiredException e) {
            return sessionExpired();
        } catch (TokenizationRateLimitException e) {
            return error(HttpStatus.TOO_MANY_REQUESTS, ApiError.TYPE_RATE_LIMIT,
                    "tokenization_rate_limit", e.getMessage());
        }
    }

    @PostMapping("/confirm")
    @Operation(summary = "Confirm payment with a tokenized payment method (SDK)")
    public ResponseEntity<?> confirmPayment(
            @Valid @RequestBody ConfirmSessionRequest request,
            @AuthenticationPrincipal NexusPayPrincipal principal) {

        try {
            // B-029 GUARD: tenant + rail come from the TRUSTED session principal, never the client body.
            // The merchant metadata forwarded to the gate is the SERVER-STORED session metadata (set at
            // /v1/payment-sessions), NOT the client request body — so a client cannot inject
            // correlation/authority keys or fabricate a tenant here. createPayment() routes through the
            // @Primary GatedPaymentGateway, which persists the INT-1 webhook-metadata row keyed by the
            // gateway payment id under the trusted tenant (sanitized + size-capped), achieving parity
            // with /v1/payments.
            PaymentSession session = sessionService.findById(principal.sessionId())
                    .filter(s -> !s.isExpired())
                    .orElseThrow(() -> new SessionExpiredException(principal.sessionId()));

            // INT-3: the response `mode` ("test"/"live") is SERVER-DERIVED from the authenticated key's
            // is_live (principal.live()) — never read from the request body.
            String mode = modeOf(principal);

            // INT-6 Invariant 4 — IDEMPOTENT RE-CONFIRM: a COMPLETE session short-circuits to its
            // ALREADY-RECORDED outcome. completeSession() stored the gateway payment id in
            // paymentIntentId; we re-derive the confirm status via a READ-ONLY getPayment (the session is
            // the ownership boundary — its principal owns its paymentIntentId), so a double-confirm never
            // re-charges, never re-synthesizes a webhook, and never calls completeSession again.
            if (PaymentSession.STATUS_COMPLETE.equals(session.getStatus())
                    && session.getPaymentIntentId() != null) {
                var recorded = paymentGateway.getPayment(session.getPaymentIntentId());
                return ResponseEntity.ok(ResponseMapper.toConfirmResponse(recorded, mode));
            }

            // Resolve the tokenized instrument by id (server-side token store). The token id — never raw
            // PAN — is the payment_method_data reference forwarded to the PSP; sanitize() in the metadata
            // store strips any PAN that slips through regardless.
            PaymentToken token = paymentTokenRepository.findById(request.payment_token_id()).orElse(null);
            String paymentMethodType = token != null ? token.getType() : null;
            String paymentMethodData = request.payment_token_id();

            var paymentRequest = new PaymentRequest(
                    session.getAmount(), session.getCurrency(), session.getCustomerId(),
                    paymentMethodType, paymentMethodData,
                    session.getSuccessUrl(), null,
                    "automatic",                                  // SDK confirm has no capture intent
                    "checkout-" + session.getId(),                // idempotency derived from session id
                    session.getMetadata());

            // INT-6 Invariant 3 — gate errors are NOT caught here: a PaymentException raised by the
            // @Primary GatedPaymentGateway (cross_border_blocked, fraud_blocked, *_review_hold, ...)
            // propagates to GlobalExceptionHandler.handlePaymentException, which renders the INT-2
            // envelope { error: { type, code, message, request_id } } with the correct status. Catching
            // it here would risk double-mapping and a wrong return type.
            var response = paymentGateway.createPayment(paymentRequest,
                    CallContext.interactive(principal.tenantId()));

            sessionService.completeSession(principal.sessionId(), response.gatewayPaymentId());

            // INT-6 Invariant 1/2 — return the proper, status-accurate ConfirmResponse the SDK consumes
            // (status ∈ {succeeded, processing, requires_action, failed}, plus paymentId/mode/livemode),
            // derived from the GATEWAY payment status — not the (always "complete") session status.
            return ResponseEntity.ok(ResponseMapper.toConfirmResponse(response, mode));
        } catch (SessionExpiredException e) {
            return sessionExpired();
        }
    }

    /**
     * INT-3/INT-6: the response {@code mode} is SERVER-DERIVED from the authenticated session key's
     * {@code is_live} ({@code principal.live()}) — "test" for an sk_test_ session, "live" otherwise. It
     * is NEVER read from the request body. A null principal defaults to "live" (a real principal). Mirrors
     * {@code PaymentController.modeOf} (intentional 1-liner duplication — no shared collaborator, L-054).
     */
    private static String modeOf(NexusPayPrincipal principal) {
        return (principal != null && !principal.live()) ? "test" : "live";
    }

    // --- INT-2 error envelope helpers ({ error: { type, code, message, request_id } }) ---

    private ResponseEntity<ApiErrorResponse> sessionExpired() {
        // INT-2: use the documented TYPE_SESSION taxonomy constant (not an ad-hoc string) so consumers can
        // branch on it reliably. Status (410 GONE) is unchanged.
        return error(HttpStatus.GONE, ApiError.TYPE_SESSION, "session_expired", "Payment session has expired");
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String type, String code, String message) {
        String rid = MDC.get(MDC_REQUEST_ID);
        if (rid == null || rid.isBlank()) {
            rid = UUID.randomUUID().toString();
        }
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(ApiError.of(type, code, message, rid)));
    }
}
