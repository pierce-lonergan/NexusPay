package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.RefundApiResponse;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.service.projection.PaymentProjectionQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/refunds")
@Tag(name = "Refunds", description = "Refund retrieval")
public class RefundController {

    private final PaymentGatewayPort paymentGateway;
    /** GAP-076 (critique v3 F1): read-only query service backing the new GET /v1/refunds LIST endpoint. */
    private final PaymentProjectionQueryService projectionQuery;

    public RefundController(PaymentGatewayPort paymentGateway,
                            PaymentProjectionQueryService projectionQuery) {
        this.paymentGateway = paymentGateway;
        this.projectionQuery = projectionQuery;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer') and @scopeAuth.has('refunds:read')")
    @Operation(summary = "Retrieve a refund")
    public ResponseEntity<RefundApiResponse> getRefund(@PathVariable String id) {
        var response = paymentGateway.getRefund(id);
        return ResponseEntity.ok(ResponseMapper.toRefundResponse(response));
    }

    /**
     * GAP-076 (critique v3 F1): lists the caller tenant's refunds from the durable READ-MODEL projection.
     * Tenant is ALWAYS {@code principal.tenantId()} and livemode ALWAYS {@code principal.live()} — a
     * foreign tenant's refunds are never listable (no IDOR / no count leak) and a test key lists only test
     * refunds. Optional {@code payment} (filter by parent payment_id) / {@code status} filters; {@code
     * limit} (default 20) clamped to [1,100], {@code offset} (default 0) to &ge; 0.
     *
     * <p>FORWARD-FILL CAVEAT: enumerates only refunds created AFTER the read-model shipped;
     * {@code GET /v1/refunds/{id}} still serves older ones. The list may lag a live async settlement by
     * the webhook-delivery window.</p>
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer') and @scopeAuth.has('refunds:read')")
    @Operation(summary = "List refunds (read-model projection)")
    public ResponseEntity<List<RefundApiResponse>> listRefunds(
            @RequestParam(value = "payment", required = false) String payment,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        // Tenant + livemode ALWAYS from the authenticated principal — never a client header/body.
        String tenantId = principal != null ? principal.tenantId() : null;
        boolean livemode = principal != null && principal.live();
        var rows = projectionQuery.listRefunds(tenantId, livemode, payment, status, limit, offset);
        return ResponseEntity.ok(rows.stream().map(ResponseMapper::toRefundResponse).toList());
    }
}
