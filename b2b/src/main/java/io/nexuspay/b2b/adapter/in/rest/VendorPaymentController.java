package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.adapter.in.rest.dto.CreateVendorPaymentRequest;
import io.nexuspay.b2b.adapter.in.rest.dto.VendorPaymentResponse;
import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase;
import io.nexuspay.b2b.domain.VendorPaymentMethod;
import io.nexuspay.common.tenant.CallerTenant;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for vendor payment creation, approval, and batching.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@RestController
@RequestMapping("/v1/vendor-payments")
public class VendorPaymentController {

    private final ManageVendorPaymentUseCase vendorPaymentUseCase;

    public VendorPaymentController(ManageVendorPaymentUseCase vendorPaymentUseCase) {
        this.vendorPaymentUseCase = vendorPaymentUseCase;
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
            @Valid @RequestBody List<CreateVendorPaymentRequest> requests) {

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
