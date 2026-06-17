package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.adapter.in.rest.dto.CreateVendorPaymentRequest;
import io.nexuspay.b2b.adapter.in.rest.dto.VendorPaymentResponse;
import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase;
import io.nexuspay.b2b.config.B2bProperties;
import io.nexuspay.b2b.domain.VendorPaymentMethod;
import io.nexuspay.common.tenant.CallerTenant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * REST controller for vendor payment creation, approval, and batching.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@RestController
@RequestMapping("/v1/vendor-payments")
@Validated   // SEC-28: enables method-level validation of the @Size cap on the /batch list parameter
public class VendorPaymentController {

    /**
     * SEC-28 (DoS): ABSOLUTE ceiling on the number of vendor payments per /batch request. The service does
     * one DB write per element in a SINGLE transaction, so an unbounded list is a resource-exhaustion /
     * long-transaction vector. This is a defense-in-depth, NON-bypassable hard cap enforced by Spring
     * method validation ({@code @Size}, which requires a compile-time constant). The OPERATIONAL limit is
     * the configurable {@code nexuspay.b2b.vendor-payment.batch-max-size} ({@link B2bProperties}, default
     * 100), enforced programmatically in {@link #createBatch} and clamped to never exceed this ceiling.
     * An oversized batch is rejected with 400 BEFORE any DB work; a caller with more payments must page
     * across multiple batch requests.
     */
    static final int MAX_BATCH_SIZE = 100;

    private final ManageVendorPaymentUseCase vendorPaymentUseCase;
    private final B2bProperties b2bProperties;

    public VendorPaymentController(ManageVendorPaymentUseCase vendorPaymentUseCase,
                                   B2bProperties b2bProperties) {
        this.vendorPaymentUseCase = vendorPaymentUseCase;
        this.b2bProperties = b2bProperties;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<VendorPaymentResponse> createPayment(
            @Valid @RequestBody CreateVendorPaymentRequest request) {

        var result = vendorPaymentUseCase.createVendorPayment(toCommand(request, CallerTenant.require()));
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<VendorPaymentResponse> getPayment(
            @PathVariable String paymentId) {

        var result = vendorPaymentUseCase.getVendorPayment(paymentId, CallerTenant.require());
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{paymentId}/approve")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<VendorPaymentResponse> approvePayment(
            @PathVariable String paymentId) {

        var result = vendorPaymentUseCase.approveVendorPayment(paymentId, CallerTenant.require());
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/batch")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<List<VendorPaymentResponse>> createBatch(
            // SEC-28: cap the batch so an oversized list is rejected 400 BEFORE any DB write (the service
            // does one write per element in one tx). @Size is the NON-bypassable defense-in-depth ceiling
            // (MAX_BATCH_SIZE); the configurable operational limit is enforced programmatically below.
            // @NotEmpty keeps a no-op empty batch out; @Valid still cascades per-element field validation.
            @NotEmpty
            @Size(max = MAX_BATCH_SIZE, message = "batch size must not exceed " + MAX_BATCH_SIZE)
            @Valid @RequestBody List<CreateVendorPaymentRequest> requests) {

        // SEC-28: enforce the CONFIGURABLE operational cap (nexuspay.b2b.vendor-payment.batch-max-size)
        // BEFORE any DB work, clamped to MAX_BATCH_SIZE so a misconfigured property can never widen the
        // hard ceiling. Throwing ResponseStatusException(400) keeps this a client error (mapped to a 400
        // envelope by gateway-api GlobalExceptionHandler), not a 500. @Size above still rejects anything
        // over the ceiling even if this check is bypassed.
        int configuredMax = Math.min(b2bProperties.getVendorPayment().getBatchMaxSize(), MAX_BATCH_SIZE);
        if (requests.size() > configuredMax) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "batch size must not exceed " + configuredMax);
        }

        String tenantId = CallerTenant.require();
        List<ManageVendorPaymentUseCase.CreateVendorPaymentCommand> commands = requests.stream()
                .map(r -> toCommand(r, tenantId))
                .toList();

        var results = vendorPaymentUseCase.createBatch(commands, tenantId);

        List<VendorPaymentResponse> responses = results.stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.status(HttpStatus.CREATED).body(responses);
    }

    private ManageVendorPaymentUseCase.CreateVendorPaymentCommand toCommand(
            CreateVendorPaymentRequest request, String tenantId) {
        return new ManageVendorPaymentUseCase.CreateVendorPaymentCommand(
                tenantId, request.vendorId(), request.amount(), request.currency(),
                VendorPaymentMethod.valueOf(request.method()),
                request.remittanceInfo(), request.scheduledAt());
    }

    private VendorPaymentResponse toResponse(ManageVendorPaymentUseCase.VendorPaymentResult result) {
        return new VendorPaymentResponse(
                result.paymentId(), result.vendorId(), result.amount(), result.currency(),
                result.method().name(), result.status().name(), result.batchId(),
                result.remittanceInfo(), result.externalReference(),
                result.scheduledAt(), result.paidAt(), result.createdAt());
    }
}
