package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.adapter.in.rest.dto.CreateInvoiceRequest;
import io.nexuspay.b2b.adapter.in.rest.dto.InvoiceResponse;
import io.nexuspay.b2b.application.port.in.ManageB2bInvoiceUseCase;
import io.nexuspay.common.tenant.CallerTenant;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for B2B invoice lifecycle management.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@RestController
@RequestMapping("/v1/b2b-invoices")
public class B2bInvoiceController {

    private final ManageB2bInvoiceUseCase invoiceUseCase;

    public B2bInvoiceController(ManageB2bInvoiceUseCase invoiceUseCase) {
        this.invoiceUseCase = invoiceUseCase;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<InvoiceResponse> createInvoice(
            @Valid @RequestBody CreateInvoiceRequest request) {

        // SEC-23: tenant resolved from the authenticated principal, never from a client X-Tenant-Id header.
        var result = invoiceUseCase.createInvoiceFromPO(
                request.purchaseOrderId(), CallerTenant.require(), request.invoiceNumber());

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @GetMapping("/{invoiceId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<InvoiceResponse> getInvoice(
            @PathVariable String invoiceId) {

        var result = invoiceUseCase.getInvoice(invoiceId, CallerTenant.require());
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{invoiceId}/send")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<InvoiceResponse> sendInvoice(
            @PathVariable String invoiceId) {

        var result = invoiceUseCase.sendInvoice(invoiceId, CallerTenant.require());
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{invoiceId}/mark-paid")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<InvoiceResponse> markInvoicePaid(
            @PathVariable String invoiceId) {

        var result = invoiceUseCase.markInvoicePaid(invoiceId, CallerTenant.require());
        return ResponseEntity.ok(toResponse(result));
    }

    private InvoiceResponse toResponse(ManageB2bInvoiceUseCase.InvoiceResult result) {
        return new InvoiceResponse(
                result.invoiceId(), result.purchaseOrderId(), result.invoiceNumber(),
                result.buyerId(), result.sellerId(), result.amount(), result.taxAmount(),
                result.currency(), result.status().name(), result.terms().name(),
                result.dueDate(), result.paidAt(), result.createdAt());
    }
}
