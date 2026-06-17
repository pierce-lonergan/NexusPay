package io.nexuspay.billing.adapter.in.rest;

import io.nexuspay.billing.application.service.InvoiceGenerationService;
import io.nexuspay.billing.domain.Invoice;
import io.nexuspay.billing.domain.InvoiceLineItem;
import io.nexuspay.common.tenant.CallerTenant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for invoices.
 *
 * @since 0.2.5 (Sprint 2.5a)
 */
@RestController
@RequestMapping("/v1/invoices")
public class InvoiceController {

    private final InvoiceGenerationService invoiceService;

    public InvoiceController(InvoiceGenerationService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping
    public ResponseEntity<List<InvoiceResponse>> list(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        // SEC-26: tenant resolved from the authenticated principal, never from a client X-Tenant-Id header.
        String tenantId = CallerTenant.require();
        return ResponseEntity.ok(invoiceService.listByTenant(tenantId, limit, offset)
                .stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvoiceResponse> get(@PathVariable String id) {
        // SEC-26: by-id read scoped to the caller's tenant — a foreign-tenant id 404s (no oracle).
        return invoiceService.findById(id, CallerTenant.require())
                .map(inv -> ResponseEntity.ok(toResponse(inv)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<Map<String, Object>> pay(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {

        String paymentMethodId = body.get("payment_method_id");
        if (paymentMethodId == null) return ResponseEntity.badRequest().build();

        // SEC-26: load scoped to the caller's tenant so a tenant-A caller cannot pay a tenant-B invoice.
        Invoice invoice = invoiceService.findById(id, CallerTenant.require()).orElse(null);
        if (invoice == null) return ResponseEntity.notFound().build();

        boolean success = invoiceService.collectPayment(invoice, paymentMethodId);
        return ResponseEntity.ok(Map.of("success", success, "invoice_id", id));
    }

    @GetMapping("/{id}/line-items")
    public ResponseEntity<List<LineItemResponse>> getLineItems(@PathVariable String id) {
        // SEC-26: line items are gated behind tenant-scoped invoice ownership (404 on foreign id).
        return ResponseEntity.ok(invoiceService.getLineItems(id, CallerTenant.require())
                .stream().map(this::toLineItemResponse).toList());
    }

    // ---- DTOs ----

    private InvoiceResponse toResponse(Invoice inv) {
        return new InvoiceResponse(
                inv.getId(), inv.getSubscriptionId(), inv.getCustomerId(),
                inv.getStatus().name(), inv.getCurrency(),
                inv.getSubtotal(), inv.getTax(), inv.getTotal(),
                inv.getAmountPaid(), inv.getAmountDue(),
                inv.getPaymentId(),
                inv.getDueDate() != null ? inv.getDueDate().toString() : null,
                inv.getPaidAt() != null ? inv.getPaidAt().toString() : null,
                inv.getPeriodStart() != null ? inv.getPeriodStart().toString() : null,
                inv.getPeriodEnd() != null ? inv.getPeriodEnd().toString() : null,
                inv.getCreatedAt().toString()
        );
    }

    private LineItemResponse toLineItemResponse(InvoiceLineItem li) {
        return new LineItemResponse(li.getId(), li.getDescription(), li.getAmount(),
                li.getCurrency(), li.getQuantity(), li.isProration());
    }

    record InvoiceResponse(String id, String subscriptionId, String customerId,
                            String status, String currency,
                            long subtotal, long tax, long total,
                            long amountPaid, long amountDue,
                            String paymentId, String dueDate, String paidAt,
                            String periodStart, String periodEnd, String createdAt) {}

    record LineItemResponse(String id, String description, long amount,
                             String currency, int quantity, boolean proration) {}
}
