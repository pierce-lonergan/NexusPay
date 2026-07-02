package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.adapter.in.rest.dto.B2bApprovalPendingResponse;
import io.nexuspay.b2b.adapter.in.rest.dto.CreatePurchaseOrderRequest;
import io.nexuspay.b2b.adapter.in.rest.dto.PurchaseOrderResponse;
import io.nexuspay.b2b.application.port.in.ManagePurchaseOrderUseCase;
import io.nexuspay.b2b.application.service.PurchaseOrderService;
import io.nexuspay.b2b.config.B2bProperties;
import io.nexuspay.b2b.domain.LineItem;
import io.nexuspay.b2b.domain.PaymentTerms;
import io.nexuspay.common.tenant.CallerTenant;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * REST controller for purchase order lifecycle management.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@RestController
@RequestMapping("/v1/purchase-orders")
public class PurchaseOrderController {

    private final ManagePurchaseOrderUseCase purchaseOrderUseCase;
    private final B2bProperties b2bProperties;

    public PurchaseOrderController(ManagePurchaseOrderUseCase purchaseOrderUseCase,
                                   B2bProperties b2bProperties) {
        this.purchaseOrderUseCase = purchaseOrderUseCase;
        this.b2bProperties = b2bProperties;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<PurchaseOrderResponse> createPurchaseOrder(
            @Valid @RequestBody CreatePurchaseOrderRequest request,
            @AuthenticationPrincipal NexusPayPrincipal principal) {

        PaymentTerms terms = request.terms() != null
                ? PaymentTerms.valueOf(request.terms())
                : PaymentTerms.NET_30;

        List<LineItem> lineItems = request.lineItems() != null
                ? request.lineItems().stream()
                    .map(li -> new LineItem(li.description(), li.quantity(), li.unitCost(),
                            li.commodityCode(), li.unitOfMeasure()))
                    .toList()
                : List.of();

        // SEC-23: tenant resolved from the authenticated principal, never from a client X-Tenant-Id header.
        // GAP-068: the creating principal is stamped (nullable-lenient for create).
        var result = purchaseOrderUseCase.createPurchaseOrder(
                new ManagePurchaseOrderUseCase.CreatePurchaseOrderCommand(
                        CallerTenant.require(), request.buyerId(), request.sellerId(),
                        request.poNumber(), request.currency(), terms,
                        request.taxAmount(), lineItems,
                        principal != null ? principal.userId() : null));

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @GetMapping("/{poId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<PurchaseOrderResponse> getPurchaseOrder(
            @PathVariable String poId) {

        var result = purchaseOrderUseCase.getPurchaseOrder(poId, CallerTenant.require());
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{poId}/submit")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<PurchaseOrderResponse> submitPurchaseOrder(
            @PathVariable String poId) {

        var result = purchaseOrderUseCase.submitPurchaseOrder(poId, CallerTenant.require());
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{poId}/approve")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<?> approvePurchaseOrder(
            @PathVariable String poId,
            @AuthenticationPrincipal NexusPayPrincipal principal) {

        // GAP-068 FAIL-CLOSED: the maker identity must be attributable (see VendorPaymentController).
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "approval requires an identifiable principal");
        }

        var outcome = purchaseOrderUseCase.approvePurchaseOrder(
                poId, CallerTenant.require(), principal.userId());
        if (outcome.requiresApproval()) {
            // INT-2 refund-contract mirror: 202 + requires_approval=true + approval id + threshold.
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new B2bApprovalPendingResponse(
                    Boolean.TRUE, outcome.pendingApprovalId(), "pending_approval",
                    PurchaseOrderService.ACTION_PURCHASE_ORDER_APPROVE, poId,
                    b2bProperties.getApprovalThreshold()));
        }
        return ResponseEntity.ok(toResponse(outcome.purchaseOrder()));
    }

    @PostMapping("/{poId}/cancel")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Void> cancelPurchaseOrder(
            @PathVariable String poId) {

        purchaseOrderUseCase.cancelPurchaseOrder(poId, CallerTenant.require());
        return ResponseEntity.noContent().build();
    }

    // Package-private static so B2bApprovalController (same package) reuses the exact response shape.
    static PurchaseOrderResponse toResponse(ManagePurchaseOrderUseCase.PurchaseOrderResult result) {
        List<PurchaseOrderResponse.LineItemDto> lineItems = result.lineItems() != null
                ? result.lineItems().stream()
                    .map(li -> new PurchaseOrderResponse.LineItemDto(
                            li.description(), li.quantity(), li.unitCost(),
                            li.commodityCode(), li.unitOfMeasure()))
                    .toList()
                : List.of();

        return new PurchaseOrderResponse(
                result.poId(), result.poNumber(), result.buyerId(), result.sellerId(),
                result.amount(), result.taxAmount(), result.currency(),
                result.status().name(), result.terms().name(), result.dueDate(),
                lineItems, result.createdAt());
    }
}
