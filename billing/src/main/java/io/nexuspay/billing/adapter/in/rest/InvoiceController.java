package io.nexuspay.billing.adapter.in.rest;

import io.nexuspay.billing.application.service.InvoiceGenerationService;
import io.nexuspay.billing.application.service.SubscriptionLifecycleService;
import io.nexuspay.billing.domain.Invoice;
import io.nexuspay.billing.domain.InvoiceLineItem;
import io.nexuspay.common.mode.PaymentMode;
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
    private final SubscriptionLifecycleService subscriptionService;

    public InvoiceController(InvoiceGenerationService invoiceService,
                             SubscriptionLifecycleService subscriptionService) {
        this.invoiceService = invoiceService;
        this.subscriptionService = subscriptionService;
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
        String tenantId = CallerTenant.require();
        Invoice invoice = invoiceService.findById(id, tenantId).orElse(null);
        if (invoice == null) return ResponseEntity.notFound().build();

        // DX-5a (MONEY-SAFETY): resolve the DURABLE test/live mode for this manual pay by composing the
        // request-thread mode and the owning subscription's durable is_live, taking the SAFER (test-wins)
        // of the two — route to the real PSP ONLY when BOTH say live. This closes the gap in BOTH
        // directions: a LIVE-key operator cannot push a TEST subscription's invoice to the real PSP, AND
        // a TEST-key operator cannot push a LIVE subscription's invoice to the real PSP (the request-thread
        // TEST->mock fail-closed must never be overridden by the subscription's mode). A non-subscription
        // (one-off) invoice or a subscription that can't be resolved falls back to the caller's request mode.
        boolean live = resolveLiveMode(invoice, tenantId);

        boolean success = invoiceService.collectPayment(invoice, paymentMethodId, live);
        return ResponseEntity.ok(Map.of("success", success, "invoice_id", id));
    }

    @GetMapping("/{id}/line-items")
    public ResponseEntity<List<LineItemResponse>> getLineItems(@PathVariable String id) {
        // SEC-26: line items are gated behind tenant-scoped invoice ownership (404 on foreign id).
        return ResponseEntity.ok(invoiceService.getLineItems(id, CallerTenant.require())
                .stream().map(this::toLineItemResponse).toList());
    }

    /**
     * DX-5a: the durable test/live mode to charge this invoice under. For a subscription-backed invoice
     * we COMPOSE the request-scoped {@code PaymentMode} with the owning subscription's persisted
     * {@code is_live}, taking the SAFER (test-wins) of the two — {@code live = isLiveExplicit() &&
     * sub.isLive()}. The charge reaches the real PSP ONLY when the request key is affirmatively LIVE AND
     * the subscription is LIVE; if EITHER is test (or the request mode is unset), it routes to the mock.
     * This closes the hole in both directions:
     * <ul>
     *   <li>a LIVE-key operator paying a TEST subscription's invoice → {@code true && false = false} → mock
     *       (the DX-5a hardening); and</li>
     *   <li>a TEST-key operator paying a LIVE subscription's invoice → {@code false && true = false} → mock
     *       (preserves the pre-existing request-thread TEST->mock fail-closed; the subscription's LIVE
     *       mode must NOT override it — a {@code sk_test_} request can never reach the real PSP).</li>
     * </ul>
     * A non-subscription (one-off) invoice or a subscription that cannot be resolved falls back to the
     * caller's request-scoped {@code PaymentMode} (unset/test → false → mock; fail-closed on a request thread).
     */
    private boolean resolveLiveMode(Invoice invoice, String tenantId) {
        String subscriptionId = invoice.getSubscriptionId();
        if (subscriptionId != null) {
            return subscriptionService.findById(subscriptionId, tenantId)
                    .map(sub -> PaymentMode.isLiveExplicit() && sub.isLive())
                    .orElseGet(PaymentMode::isLiveExplicit);
        }
        return PaymentMode.isLiveExplicit();
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
