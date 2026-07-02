package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.application.port.in.ManagePurchaseOrderUseCase;
import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase;
import io.nexuspay.b2b.application.port.out.B2bEventPublisher;
import io.nexuspay.b2b.domain.PurchaseOrderStatus;
import io.nexuspay.b2b.domain.VendorPaymentStatus;
import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.exception.ConflictException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.PendingApproval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * GAP-068: the b2b maker-checker REVIEW path. REUSES the iam {@link ApprovalService} machinery
 * (atomic PENDING→APPROVED claim = execute-once B-009; requester != reviewer enforced inside
 * {@code ApprovalService.approve}; tenant-checked load, 404-no-oracle) through the declared
 * b2b→iam module edge — never writing another module's table directly.
 *
 * <p><b>Fail-closed checks, in order:</b> (1) the approval must exist, belong to the caller's
 * tenant, and carry a b2b action — anything else is a uniform 404 (no existence oracle);
 * (2) creator != approver: when the payload recorded {@code created_by} and it equals the
 * reviewer, the review is FORBIDDEN before any claim is attempted; (3) requester != reviewer +
 * the atomic claim, inside {@code ApprovalService.approve}.</p>
 *
 * <p><b>One-transaction execute-once:</b> because the b2b vendor rail is the in-process stub (not
 * external IO like the refund PSP), the claim, the resource execution, the GAP-069 ledger
 * postings, and the action-agnostic {@code executed_at} marker all commit or roll back as ONE
 * transaction. A failure anywhere returns the {@code pending_approvals} row to PENDING with zero
 * ledger residue — no B-022 stuck-APPROVED class exists on this path. Double-approve loses the
 * atomic claim (0 rows → IllegalStateException) so execution happens exactly once.</p>
 *
 * @since GAP-068 (WAVE1-money-ledger)
 */
@Service
public class B2bApprovalService {

    private static final Logger log = LoggerFactory.getLogger(B2bApprovalService.class);

    private static final Set<String> B2B_ACTIONS = Set.of(
            VendorPaymentService.ACTION_VENDOR_PAYMENT_APPROVE,
            PurchaseOrderService.ACTION_PURCHASE_ORDER_APPROVE);

    private final ApprovalService approvalService;
    private final ManageVendorPaymentUseCase vendorPayments;
    private final ManagePurchaseOrderUseCase purchaseOrders;
    private final B2bEventPublisher eventPublisher;

    public B2bApprovalService(ApprovalService approvalService,
                              ManageVendorPaymentUseCase vendorPayments,
                              ManagePurchaseOrderUseCase purchaseOrders,
                              B2bEventPublisher eventPublisher) {
        this.approvalService = approvalService;
        this.vendorPayments = vendorPayments;
        this.purchaseOrders = purchaseOrders;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Outcome of an approve review: the claimed approval plus the EXECUTED resource state (exactly
     * one of {@code vendorPayment}/{@code purchaseOrder} is non-null, keyed by the action).
     */
    public record ReviewResult(
            PendingApproval approval,
            ManageVendorPaymentUseCase.VendorPaymentResult vendorPayment,
            ManagePurchaseOrderUseCase.PurchaseOrderResult purchaseOrder) {}

    /**
     * {@code noRollbackFor}: the STALE-APPROVAL conversion below terminally rejects the row and then
     * throws a 409 {@link ConflictException} — that reject write must COMMIT (nothing money-related
     * has happened at that point; the claim/execution path throws other exception types, which all
     * still roll back).
     */
    @Transactional(noRollbackFor = ConflictException.class)
    public ReviewResult reviewApprove(String approvalId, String reviewerId, String tenantId) {
        PendingApproval approval = loadForReview(approvalId, tenantId);

        // FAIL-CLOSED creator check (GAP-068): the principal that CREATED the resource may not
        // approve its money movement, even if a different principal requested the approval. Runs
        // BEFORE any claim so a forbidden review leaves the row PENDING. (created_by is nullable
        // for legacy rows — requester != reviewer below is always enforced regardless.)
        Object createdBy = approval.getPayload() != null ? approval.getPayload().get("created_by") : null;
        if (createdBy != null && createdBy.equals(reviewerId)) {
            throw AuthorizationException.forbidden("approve a resource you created");
        }

        // WAVE1 review fix — STALE-APPROVAL conversion: a still-PENDING approval whose resource has
        // already left the approvable state (e.g. the PO was cancelled while the approval was
        // pending) can NEVER execute — without this check every review attempt claims it, fails the
        // domain state guard inside executeApproved, rolls back to PENDING, and surfaces as a 500
        // forever (a permanent zombie in GET /v1/approvals). Convert it to terminal REJECTED and
        // 409 instead, BEFORE any claim/money movement, so nothing money-related is at stake when
        // noRollbackFor lets the reject commit. A resource that goes stale in the race window
        // between this check and the claim still fails safely (domain guard -> full rollback ->
        // PENDING) and is converted on the next attempt.
        if (approval.isPending() && !resourceStillApprovable(approval, tenantId)) {
            approvalService.reject(approvalId, reviewerId, tenantId);
            publishRejected(approval, reviewerId, tenantId, "stale_resource");
            log.info("B2B approval {} rejected as stale: resource {} no longer approvable",
                    approvalId, approval.getResourceId());
            throw new ConflictException(
                    "Approval targets a resource that is no longer approvable; "
                            + "the approval has been rejected", "stale_approval");
        }

        // iam enforces requester != reviewer and the atomic PENDING->APPROVED claim (execute-once,
        // B-009): a concurrent double-approve loses the claim and throws — it never executes.
        PendingApproval claimed = approvalService.approve(approvalId, reviewerId, tenantId);

        // Execute the underlying b2b action IN THIS TRANSACTION (in-process rail): state transition +
        // GAP-069 ledger postings are atomic with the claim; any failure rolls the claim back to
        // PENDING with zero ledger residue.
        ReviewResult result;
        if (VendorPaymentService.ACTION_VENDOR_PAYMENT_APPROVE.equals(claimed.getAction())) {
            var vp = vendorPayments.executeApproved(claimed.getResourceId(), tenantId);
            result = new ReviewResult(claimed, vp, null);
        } else {
            var po = purchaseOrders.executeApproved(claimed.getResourceId(), tenantId);
            result = new ReviewResult(claimed, null, po);
        }

        // Action-agnostic executed_at marker (conditional UPDATE ... WHERE executed_at IS NULL),
        // committed atomically with the execution above.
        approvalService.markRefundExecuted(approvalId, tenantId);

        log.info("B2B approval executed: id={}, action={}, reviewer={}", approvalId,
                claimed.getAction(), reviewerId);
        return result;
    }

    @Transactional
    public PendingApproval reviewReject(String approvalId, String reviewerId, String tenantId) {
        PendingApproval approval = loadForReview(approvalId, tenantId);

        PendingApproval rejected = approvalService.reject(approvalId, reviewerId, tenantId);

        publishRejected(approval, reviewerId, tenantId, null);

        log.info("B2B approval rejected: id={}, action={}, reviewer={}", approvalId,
                approval.getAction(), reviewerId);
        return rejected;
    }

    /**
     * Emits the taxonomy rejection event ({@code VendorPaymentApprovalRejected} /
     * {@code PurchaseOrderApprovalRejected}) — the single emitter for BOTH the explicit reject path
     * and the stale-approval conversion, so consumers see every b2b rejection. {@code reason} is
     * additive payload (e.g. {@code stale_resource}), not a new event type.
     */
    private void publishRejected(PendingApproval approval, String reviewerId, String tenantId,
                                 String reason) {
        boolean isVendorPayment =
                VendorPaymentService.ACTION_VENDOR_PAYMENT_APPROVE.equals(approval.getAction());
        String eventType = isVendorPayment
                ? "VendorPaymentApprovalRejected"
                : "PurchaseOrderApprovalRejected";
        String aggregateType = isVendorPayment ? "VendorPayment" : "PurchaseOrder";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", approval.getId());
        payload.put("rejectedBy", reviewerId);
        payload.put("tenantId", tenantId);
        if (reason != null) {
            payload.put("reason", reason);
        }
        eventPublisher.publishEvent(aggregateType, approval.getResourceId(), eventType, payload,
                tenantId);
    }

    /**
     * WAVE1 review fix: is the approval's resource still in the ONLY state its execution path can
     * accept (mirrors the guards in {@code approveVendorPayment} / {@code approvePurchaseOrder})?
     * A vanished resource is definitively stale.
     */
    private boolean resourceStillApprovable(PendingApproval approval, String tenantId) {
        try {
            if (VendorPaymentService.ACTION_VENDOR_PAYMENT_APPROVE.equals(approval.getAction())) {
                return vendorPayments.getVendorPayment(approval.getResourceId(), tenantId)
                        .status() == VendorPaymentStatus.PENDING;
            }
            return purchaseOrders.getPurchaseOrder(approval.getResourceId(), tenantId)
                    .status() == PurchaseOrderStatus.SUBMITTED;
        } catch (ResourceNotFoundException gone) {
            return false;
        }
    }

    /**
     * Uniform 404-no-oracle load (the iam {@code loadForReview} mirror): absent, wrong-tenant, and
     * non-b2b-action all collapse into one not-found — a b2b reviewer can neither probe foreign
     * tenants' approvals nor hijack another module's (e.g. refund) approvals through this path.
     */
    private PendingApproval loadForReview(String approvalId, String tenantId) {
        PendingApproval approval = approvalService.findById(approvalId)
                .orElseThrow(() -> new ResourceNotFoundException("Approval not found"));
        if (!tenantId.equals(approval.getTenantId())) {
            throw new ResourceNotFoundException("Approval not found");
        }
        if (!B2B_ACTIONS.contains(approval.getAction())) {
            throw new ResourceNotFoundException("Approval not found");
        }
        return approval;
    }
}
