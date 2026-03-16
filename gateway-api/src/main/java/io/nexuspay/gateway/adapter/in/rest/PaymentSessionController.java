package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.CreatePaymentSessionRequest;
import io.nexuspay.gateway.adapter.in.rest.dto.PaymentSessionResponse;
import io.nexuspay.gateway.application.port.in.CreatePaymentSessionUseCase;
import io.nexuspay.gateway.application.port.in.CreatePaymentSessionUseCase.CreateSessionCommand;
import io.nexuspay.gateway.application.service.PaymentSessionService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Server-side API for managing payment sessions.
 * Authenticated via API key or JWT (merchant-facing).
 *
 * @since 0.3.5 (Sprint 3.5)
 */
@RestController
@RequestMapping("/v1/payment-sessions")
@Tag(name = "Payment Sessions", description = "Manage checkout SDK payment sessions")
public class PaymentSessionController {

    private final CreatePaymentSessionUseCase createSessionUseCase;
    private final PaymentSessionService sessionService;

    public PaymentSessionController(CreatePaymentSessionUseCase createSessionUseCase,
                                     PaymentSessionService sessionService) {
        this.createSessionUseCase = createSessionUseCase;
        this.sessionService = sessionService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    @Operation(summary = "Create a payment session for the checkout SDK")
    public ResponseEntity<PaymentSessionResponse> createSession(
            @Valid @RequestBody CreatePaymentSessionRequest request,
            @AuthenticationPrincipal NexusPayPrincipal principal) {

        var result = createSessionUseCase.create(new CreateSessionCommand(
                principal.tenantId(),
                request.amount(), request.currency(), request.customer_id(),
                request.success_url(), request.cancel_url(),
                request.allowed_payment_methods(),
                request.branding(), request.metadata()
        ));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseMapper.toPaymentSessionResponse(result.session(), result.token().token()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    @Operation(summary = "Retrieve a payment session")
    public ResponseEntity<PaymentSessionResponse> getSession(@PathVariable String id) {
        return sessionService.findById(id)
                .map(s -> ResponseEntity.ok(ResponseMapper.toPaymentSessionResponse(s, null)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/expire")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    @Operation(summary = "Force-expire a payment session")
    public ResponseEntity<Void> expireSession(@PathVariable String id) {
        sessionService.expireSession(id);
        return ResponseEntity.noContent().build();
    }
}
