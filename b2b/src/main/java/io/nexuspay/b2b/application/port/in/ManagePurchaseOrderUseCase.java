package io.nexuspay.b2b.application.port.in;

import io.nexuspay.b2b.domain.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Use case for creating and managing purchase orders.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public interface ManagePurchaseOrderUseCase {

    PurchaseOrderResult createPurchaseOrder(CreatePurchaseOrderCommand command);

    PurchaseOrderResult getPurchaseOrder(String poId, String tenantId);

    PurchaseOrderResult submitPurchaseOrder(String poId, String tenantId);

    PurchaseOrderResult approvePurchaseOrder(String poId, String tenantId);

    void cancelPurchaseOrder(String poId, String tenantId);

    record CreatePurchaseOrderCommand(
            String tenantId,
            String buyerId,
            String sellerId,
            String poNumber,
            String currency,
            PaymentTerms terms,
            long taxAmount,
            List<LineItem> lineItems
    ) {}

    record PurchaseOrderResult(
            String poId,
            String poNumber,
            String buyerId,
            String sellerId,
            long amount,
            long taxAmount,
            String currency,
            PurchaseOrderStatus status,
            PaymentTerms terms,
            LocalDate dueDate,
            List<LineItem> lineItems,
            Instant createdAt
    ) {}
}
