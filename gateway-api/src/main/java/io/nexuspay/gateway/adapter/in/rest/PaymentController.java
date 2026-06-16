package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.*;
import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.CallContext;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.domain.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/payments")
@Tag(name = "Payments", description = "Payment lifecycle operations")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentGatewayPort paymentGateway;
    private final RefundOrchestrationService refundOrchestration;
    private final ScreeningOriginService screeningOrigins;

    public PaymentController(PaymentGatewayPort paymentGateway,
                              RefundOrchestrationService refundOrchestration,
                              ScreeningOriginService screeningOrigins) {
        this.paymentGateway = paymentGateway;
        this.refundOrchestration = refundOrchestration;
        this.screeningOrigins = screeningOrigins;
    }

    /**
     * Trusted edge-stamped source-country header. A reverse proxy / CDN at the trusted edge
     * (e.g. Cloudflare {@code CF-IPCountry}) sets this; the application stamps it into a
     * server-only metadata key that the sanctions geography resolver reads. If no such edge is
     * provisioned the header is absent → source is treated as UNKNOWN (B-025 fail-closed), never
     * inferred from the client-supplied {@code source_country}/{@code ip_country}.
     */
    private static final String EDGE_IP_COUNTRY_HEADER = "CF-IPCountry";

    /** Server-only metadata key the geography resolver reads. Never client-settable. */
    private static final String TRUSTED_IP_COUNTRY_KEY = "ip_country_trusted";

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    @Operation(summary = "Create a payment intent")
    public ResponseEntity<PaymentApiResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = EDGE_IP_COUNTRY_HEADER, required = false) String edgeIpCountry,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        // Interactive flow. Stamp the TRUSTED tenant from the authenticated principal and
        // strip any client-supplied server-rail markers ("source"/"workflow") so a caller
        // cannot claim the softer SERVER_RECURRING/SERVER_OTHER screening. The B-024
        // @Primary GatedPaymentGateway runs the fraud + sanctions screen at the port boundary.
        Map<String, Object> metadata = new HashMap<>();
        if (request.metadata() != null) {
            metadata.putAll(request.metadata());
        }
        metadata.remove("source");
        metadata.remove("workflow");
        // B-025: NEVER let the client pre-seed the trusted source key. Strip it unconditionally,
        // then stamp it ONLY from the trusted edge header (when the edge provisioned one).
        metadata.remove(TRUSTED_IP_COUNTRY_KEY);
        if (edgeIpCountry != null && !edgeIpCountry.isBlank() && !"XX".equalsIgnoreCase(edgeIpCountry.trim())) {
            // "XX"/"T1" are Cloudflare's "unknown"/"Tor" sentinels — treat as unknown (leave unset).
            String cc = edgeIpCountry.trim();
            if (cc.length() == 2 && !"T1".equalsIgnoreCase(cc)) {
                metadata.put(TRUSTED_IP_COUNTRY_KEY, cc.toUpperCase());
            }
        }
        if (principal != null && principal.tenantId() != null) {
            metadata.put("tenant_id", principal.tenantId());
        }

        // INT-2 Invariant 1: `capture` is a convenience alias for `capture_method`. `capture_method` is
        // authoritative when both are present; the boolean alias only fills a null/blank capture_method
        // (true→automatic, false→manual). Pure passthrough when the alias is absent (back-compat).
        String captureMethod = request.capture_method();
        if ((captureMethod == null || captureMethod.isBlank()) && request.capture() != null) {
            captureMethod = request.capture() ? "automatic" : "manual";
        } else if (captureMethod != null && !captureMethod.isBlank() && request.capture() != null) {
            log.debug("Both capture_method and capture supplied; capture_method is authoritative "
                    + "(capture alias ignored)");
        }

        var paymentRequest = new PaymentRequest(
                request.amount(), request.currency(), request.customer_id(),
                request.payment_method_type(), request.payment_method_data(),
                request.return_url(), request.description(), captureMethod,
                idempotencyKey, metadata);

        // B-029: derive the screening rail + tenant from the TRUSTED authenticated principal, not
        // from client metadata. The strip/stamp above stays as belt-and-suspenders; the gate no
        // longer depends on it for authority.
        String tenantId = principal != null ? principal.tenantId() : null;
        var response = paymentGateway.createPayment(paymentRequest, CallContext.interactive(tenantId));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseMapper.toPaymentResponse(response));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    @Operation(summary = "Confirm a payment intent")
    public ResponseEntity<PaymentApiResponse> confirmPayment(
            @PathVariable String id,
            @RequestBody(required = false) ConfirmPaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        var req = request != null ? request : new ConfirmPaymentRequest(null, null, null);
        // B-029: assert the trusted interactive ingress identity (the server-owned origin store
        // remains the authority for the persisted intent's (tenant, mode)).
        String tenantId = principal != null ? principal.tenantId() : null;
        // SEC-07 (B-007): verify the caller's tenant owns this payment id BEFORE delegating to the PSP.
        // Fail-closed (404) on absent origin or tenant mismatch — no cross-tenant existence oracle.
        screeningOrigins.assertOwnedBy(id, tenantId);
        var response = paymentGateway.confirmPayment(id, new ConfirmRequest(
                req.payment_method_type(), req.payment_method_data(), req.return_url(), idempotencyKey),
                CallContext.interactive(tenantId));
        return ResponseEntity.ok(ResponseMapper.toPaymentResponse(response));
    }

    @PostMapping("/{id}/capture")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    @Operation(summary = "Capture an authorized payment")
    public ResponseEntity<PaymentApiResponse> capturePayment(
            @PathVariable String id,
            @RequestBody(required = false) CapturePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        // SEC-07 (B-007): tenant-ownership check BEFORE the id reaches the PSP (404 on mismatch/absent).
        screeningOrigins.assertOwnedBy(id, principal != null ? principal.tenantId() : null);
        var req = request != null ? request : new CapturePaymentRequest(null);
        var response = paymentGateway.capturePayment(id, new CaptureRequest(
                req.amount_to_capture(), idempotencyKey));
        return ResponseEntity.ok(ResponseMapper.toPaymentResponse(response));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    @Operation(summary = "Void a payment authorization")
    public ResponseEntity<PaymentApiResponse> cancelPayment(
            @PathVariable String id,
            @RequestBody(required = false) CancelPaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        // SEC-07 (B-007): tenant-ownership check BEFORE the id reaches the PSP (404 on mismatch/absent).
        screeningOrigins.assertOwnedBy(id, principal != null ? principal.tenantId() : null);
        var req = request != null ? request : new CancelPaymentRequest(null);
        var response = paymentGateway.voidPayment(id, new VoidRequest(
                req.cancellation_reason(), idempotencyKey));
        return ResponseEntity.ok(ResponseMapper.toPaymentResponse(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    @Operation(summary = "Retrieve a payment")
    public ResponseEntity<PaymentApiResponse> getPayment(
            @PathVariable String id,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        // SEC-07 (B-007): tenant-ownership check BEFORE retrieving from the PSP (404 on mismatch/absent).
        screeningOrigins.assertOwnedBy(id, principal != null ? principal.tenantId() : null);
        var response = paymentGateway.getPayment(id);
        return ResponseEntity.ok(ResponseMapper.toPaymentResponse(response));
    }

    @PostMapping("/{id}/refunds")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    @Operation(summary = "Create a refund. Returns 202 if amount exceeds approval threshold.")
    public ResponseEntity<?> createRefund(
            @PathVariable String id,
            @Valid @RequestBody CreateRefundRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        var result = refundOrchestration.createRefund(
                id, request.amount(), request.currency(), request.reason(),
                idempotencyKey, principal.userId(), principal.tenantId());

        if (result.requiresApproval()) {
            // INT-2 Invariant 3: 202 body carries requires_approval=true + the configured threshold.
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ResponseMapper.toApprovalResponse(result.pendingApproval(),
                            refundOrchestration.refundApprovalThreshold()));
        }
        // 201 body carries requires_approval=false (symmetry).
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseMapper.toRefundResponse(result.refundResponse()));
    }
}
