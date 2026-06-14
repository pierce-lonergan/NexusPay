package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.*;
import io.nexuspay.gateway.application.port.in.TokenizePaymentMethodUseCase;
import io.nexuspay.gateway.application.port.in.TokenizePaymentMethodUseCase.TokenizeCommand;
import io.nexuspay.gateway.application.service.PaymentSessionService;
import io.nexuspay.gateway.domain.SessionExpiredException;
import io.nexuspay.gateway.domain.TokenizationRateLimitException;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

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

    private final PaymentSessionService sessionService;
    private final TokenizePaymentMethodUseCase tokenizeUseCase;

    public CheckoutController(PaymentSessionService sessionService,
                               TokenizePaymentMethodUseCase tokenizeUseCase) {
        this.sessionService = sessionService;
        this.tokenizeUseCase = tokenizeUseCase;
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
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", Map.of(
                            "type", "session_error",
                            "code", "session_expired",
                            "message", "Payment session has expired")));
        } catch (TokenizationRateLimitException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", Map.of(
                            "type", "rate_limit_error",
                            "code", "tokenization_rate_limit",
                            "message", e.getMessage())));
        }
    }

    @PostMapping("/confirm")
    @Operation(summary = "Confirm payment with a tokenized payment method (SDK)")
    public ResponseEntity<?> confirmPayment(
            @Valid @RequestBody ConfirmSessionRequest request,
            @AuthenticationPrincipal NexusPayPrincipal principal) {

        try {
            // TODO: Integrate with PaymentGatewayPort to create payment intent.
            // B-029 GUARD: when this is wired, it MUST call
            //   paymentGateway.createPayment(request, CallContext.interactive(principal.tenantId()))
            // deriving the rail + tenant from the TRUSTED session principal — NEVER forwarding the
            // client request body's metadata as the screening mode/tenant. This is the SDK-facing
            // ingress B-029 specifically warns about: forwarding client metadata here would let a
            // client claim the soft SERVER_* rail or fabricate a tenant to fragment fraud velocity.
            // For now, mark session as complete
            sessionService.completeSession(principal.sessionId(), null);

            return sessionService.findById(principal.sessionId())
                    .map(session -> ResponseEntity.ok(ResponseMapper.toSessionStatusResponse(session)))
                    .orElse(ResponseEntity.status(HttpStatus.GONE).build());
        } catch (SessionExpiredException e) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", Map.of(
                            "type", "session_error",
                            "code", "session_expired",
                            "message", "Payment session has expired")));
        }
    }
}
