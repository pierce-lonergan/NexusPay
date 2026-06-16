package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.adapter.in.rest.dto.CreatePurchaseOrderRequest;
import io.nexuspay.b2b.adapter.in.rest.dto.PurchaseOrderResponse;
import io.nexuspay.b2b.application.port.in.ManagePurchaseOrderUseCase;
import io.nexuspay.b2b.domain.LineItem;
import io.nexuspay.b2b.domain.PaymentTerms;
import io.nexuspay.common.tenant.CallerTenant;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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

    public PurchaseOrderController(ManagePurchaseOrderUseCase purchaseOrderUseCase) {
        this.purchaseOrderUseCase = purchaseOrderUseCase;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<PurchaseOrderResponse> createPurchaseOrder(
            @Valid @RequestBody CreatePurchaseOrderRequest request) {

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
        var result = purchaseOrderUseCase.createPurchaseOrder(
                new ManagePurchaseOrderUseCase.CreatePurchaseOrderCommand(
                        CallerTenant.require(), request.buyerId(), request.sellerId(),
                        request.poNumber(), request.currency(), terms,
                        request.taxAmount(), lineItems));

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
    public ResponseEntity<PurchaseOrderResponse> approvePurchaseOrder(
            @PathVariable String poId) {

        var result = purchaseOrderUseCase.approvePurchaseOrder(poId, CallerTenant.require());
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{poId}/cancel")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Void> cancelPurchaseOrder(
            @PathVariable String poId) {

        purchaseOrderUseCase.cancelPurchaseOrder(poId, CallerTenant.require());
        return ResponseEntity.noContent().build();
    }

    private PurchaseOrderResponse toResponse(ManagePurchaseOrderUseCase.PurchaseOrderResult result) {
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
