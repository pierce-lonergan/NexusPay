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

    /**
     * GAP-068 maker-checker entry point. Below the configured threshold
     * ({@code nexuspay.b2b.approval-threshold}, compared against {@code amount + taxAmount}) this
     * executes single-step via {@link #executeApproved}; at/above it, a PENDING approval is created
     * and the PO stays SUBMITTED until a DIFFERENT principal reviews it.
     *
     * @param requestedBy the authenticated principal requesting the approval (the "maker")
     */
    ApproveOutcome approvePurchaseOrder(String poId, String tenantId, String requestedBy);

    /**
     * Executes the PO approval ({@code SUBMITTED -> APPROVED} + event). Posts NOTHING to the
     * ledger — an approved PO is an executory commitment, not a money movement (see the b2b
     * {@code LedgerPort} javadoc for the full PO-commitment decision).
     */
    PurchaseOrderResult executeApproved(String poId, String tenantId);

    void cancelPurchaseOrder(String poId, String tenantId);

    record CreatePurchaseOrderCommand(
            String tenantId,
            String buyerId,
            String sellerId,
            String poNumber,
            String currency,
            PaymentTerms terms,
            long taxAmount,
            List<LineItem> lineItems,
            String createdBy   // GAP-068: creating principal (nullable-lenient for non-principal creates)
    ) {}

    /**
     * GAP-068: outcome of {@link #approvePurchaseOrder} — refund-contract mirror. When
     * {@link #requiresApproval()} the PO stays SUBMITTED and {@code pendingApprovalId} names the
     * maker-checker row (202); otherwise {@code purchaseOrder} carries the APPROVED state.
     */
    record ApproveOutcome(PurchaseOrderResult purchaseOrder, String pendingApprovalId) {
        public boolean requiresApproval() {
            return pendingApprovalId != null;
        }
    }

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
