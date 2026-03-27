package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.adapter.in.rest.dto.CreatePurchaseOrderRequest;
import io.nexuspay.b2b.adapter.in.rest.dto.PurchaseOrderResponse;
import io.nexuspay.b2b.application.port.in.ManagePurchaseOrderUseCase;
import io.nexuspay.b2b.domain.LineItem;
import io.nexuspay.b2b.domain.PaymentTerms;
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
            @RequestHeader("X-Tenant-Id") String tenantId,
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

        var result = purchaseOrderUseCase.createPurchaseOrder(
                new ManagePurchaseOrderUseCase.CreatePurchaseOrderCommand(
                        tenantId, request.buyerId(), request.sellerId(),
                        request.poNumber(), request.currency(), terms,
                        request.taxAmount(), lineItems));

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @GetMapping("/{poId}")
    @PreAuthorize("hasAnyRole('admin', 'operator', 'viewer')")
    public ResponseEntity<PurchaseOrderResponse> getPurchaseOrder(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String poId) {

        var result = purchaseOrderUseCase.getPurchaseOrder(poId, tenantId);
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{poId}/submit")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<PurchaseOrderResponse> submitPurchaseOrder(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String poId) {

        var result = purchaseOrderUseCase.submitPurchaseOrder(poId, tenantId);
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{poId}/approve")
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    public ResponseEntity<PurchaseOrderResponse> approvePurchaseOrder(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String poId) {

        var result = purchaseOrderUseCase.approvePurchaseOrder(poId, tenantId);
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{poId}/cancel")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Void> cancelPurchaseOrder(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String poId) {

        purchaseOrderUseCase.cancelPurchaseOrder(poId, tenantId);
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
