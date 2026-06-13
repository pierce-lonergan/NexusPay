package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.*;
import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.domain.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

    private final PaymentGatewayPort paymentGateway;
    private final RefundOrchestrationService refundOrchestration;

    public PaymentController(PaymentGatewayPort paymentGateway,
                              RefundOrchestrationService refundOrchestration) {
        this.paymentGateway = paymentGateway;
        this.refundOrchestration = refundOrchestration;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    @Operation(summary = "Create a payment intent")
    public ResponseEntity<PaymentApiResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
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
        if (principal != null && principal.tenantId() != null) {
            metadata.put("tenant_id", principal.tenantId());
        }

        var paymentRequest = new PaymentRequest(
                request.amount(), request.currency(), request.customer_id(),
                request.payment_method_type(), request.payment_method_data(),
                request.return_url(), request.description(), request.capture_method(),
                idempotencyKey, metadata);

        var response = paymentGateway.createPayment(paymentRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseMapper.toPaymentResponse(response));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    @Operation(summary = "Confirm a payment intent")
    public ResponseEntity<PaymentApiResponse> confirmPayment(
            @PathVariable String id,
            @RequestBody(required = false) ConfirmPaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var req = request != null ? request : new ConfirmPaymentRequest(null, null, null);
        var response = paymentGateway.confirmPayment(id, new ConfirmRequest(
                req.payment_method_type(), req.payment_method_data(), req.return_url(), idempotencyKey));
        return ResponseEntity.ok(ResponseMapper.toPaymentResponse(response));
    }

    @PostMapping("/{id}/capture")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    @Operation(summary = "Capture an authorized payment")
    public ResponseEntity<PaymentApiResponse> capturePayment(
            @PathVariable String id,
            @RequestBody(required = false) CapturePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
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
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var req = request != null ? request : new CancelPaymentRequest(null);
        var response = paymentGateway.voidPayment(id, new VoidRequest(
                req.cancellation_reason(), idempotencyKey));
        return ResponseEntity.ok(ResponseMapper.toPaymentResponse(response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    @Operation(summary = "Retrieve a payment")
    public ResponseEntity<PaymentApiResponse> getPayment(@PathVariable String id) {
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
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(ResponseMapper.toApprovalResponse(result.pendingApproval()));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseMapper.toRefundResponse(result.refundResponse()));
    }
}
