package io.nexuspay.b2b.application.port.in;

import io.nexuspay.b2b.domain.VendorPaymentMethod;
import io.nexuspay.b2b.domain.VendorPaymentStatus;

import java.time.Instant;
import java.util.List;

/**
 * Use case for creating, approving, and batching vendor payments.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public interface ManageVendorPaymentUseCase {

    VendorPaymentResult createVendorPayment(CreateVendorPaymentCommand command);

    /**
     * GAP-068 maker-checker entry point. Below the configured threshold
     * ({@code nexuspay.b2b.approval-threshold}) this executes single-step via
     * {@link #executeApproved}; at/above it, a PENDING approval is created (payload carries
     * {@code created_by} for the creator != approver check) and the payment stays PENDING until a
     * DIFFERENT principal reviews it.
     *
     * @param requestedBy the authenticated principal requesting the approval (the "maker")
     */
    ApproveOutcome approveVendorPayment(String paymentId, String tenantId, String requestedBy);

    /**
     * Executes an approved vendor payment in ONE transaction: {@code PENDING -> APPROVED} (the
     * state guard is the replay defense), the accrual ledger posting, the execution-stub
     * disbursement, {@code markPaid(externalReference)}, and the disbursement ledger posting
     * (GAP-069 — postings are atomic with the transition; a failure rolls everything back).
     * Invoked directly on the below-threshold path and by the b2b approval review path.
     */
    VendorPaymentResult executeApproved(String paymentId, String tenantId);

    List<VendorPaymentResult> createBatch(List<CreateVendorPaymentCommand> commands, String tenantId);

    VendorPaymentResult getVendorPayment(String paymentId, String tenantId);

    record CreateVendorPaymentCommand(
            String tenantId,
            String vendorId,
            long amount,
            String currency,
            VendorPaymentMethod method,
            String remittanceInfo,
            Instant scheduledAt,
            String createdBy   // GAP-068: creating principal (nullable-lenient for non-principal creates)
    ) {}

    /**
     * GAP-068: outcome of {@link #approveVendorPayment} — mirrors the refund
     * {@code RefundOrchestrationService.RefundResult} contract. When {@link #requiresApproval()}
     * the payment stays PENDING and {@code pendingApprovalId} names the maker-checker row (the
     * controller returns 202); otherwise {@code payment} carries the executed (PAID) state.
     */
    record ApproveOutcome(VendorPaymentResult payment, String pendingApprovalId) {
        public boolean requiresApproval() {
            return pendingApprovalId != null;
        }
    }

    record VendorPaymentResult(
            String paymentId,
            String vendorId,
            long amount,
            String currency,
            VendorPaymentMethod method,
            VendorPaymentStatus status,
            String batchId,
            String remittanceInfo,
            String externalReference,
            Instant scheduledAt,
            Instant paidAt,
            Instant createdAt
    ) {}
}
