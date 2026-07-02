package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.exception.ConflictException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.gateway.adapter.in.rest.dto.ApprovalResponse;
import io.nexuspay.gateway.adapter.in.rest.dto.RefundApiResponse;
import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/approvals")
@Tag(name = "Approvals", description = "Maker-checker approval workflow")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final RefundOrchestrationService refundOrchestration;

    public ApprovalController(ApprovalService approvalService,
                               RefundOrchestrationService refundOrchestration) {
        this.approvalService = approvalService;
        this.refundOrchestration = refundOrchestration;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('admin', 'operator')")
    @Operation(summary = "List pending approvals")
    public ResponseEntity<List<ApprovalResponse>> listPending(
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        var approvals = approvalService.listPending(principal.tenantId()).stream()
                .map(ResponseMapper::toApprovalResponse)
                .toList();
        return ResponseEntity.ok(approvals);
    }

    @PostMapping("/{id}/approve")
    // DX-5c-ii: approving a pending request EXECUTES the underlying money action — today the only action
    // type ever created is "refund" (RefundOrchestrationService), so this endpoint SETTLES a refund. Gate
    // it with the same scope the refund-money action requires (refunds:write), AND-composed with the admin
    // role. Without this, an admin-role key explicitly scoped AWAY from refunds (e.g. payments:read only)
    // is correctly 403'd on the refund-CREATE path (POST /v1/payments/{id}/refunds) yet could still settle
    // a refund here — defeating the very narrowing the scope exists to enforce. Scopes NARROW, never widen:
    // an UNRESTRICTED (null/empty) key still passes via the back-compat hasScope==true path.
    @PreAuthorize("hasRole('admin') and @scopeAuth.has('refunds:write')")
    @Operation(summary = "Approve a pending request and execute the action")
    public ResponseEntity<?> approve(
            @PathVariable String id,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        // GAP-068 FAIL-CLOSED stranding guard: this endpoint can only EXECUTE refund approvals.
        // BEFORE claiming, load the approval (tenant-checked, 404-no-oracle) and refuse (409) any
        // action it cannot execute — otherwise a b2b approval (vendor_payment_approve /
        // purchase_order_approve) claimed here would flip APPROVED without ever executing or
        // booking its ledger entries, permanently stranding it (the B-022 reconciler filters
        // action='refund' and would never re-drive it). Today only "refund" is ever created via
        // this module, so no live flow changes; the owning module's review endpoint for b2b is
        // POST /v1/b2b/approvals/{id}/approve.
        var pending = approvalService.findById(id)
                .filter(a -> principal.tenantId().equals(a.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("Approval not found"));
        if (!"refund".equals(pending.getAction())) {
            throw new ConflictException(
                    "Approval action '" + pending.getAction()
                            + "' must be reviewed via its owning module's approval endpoint",
                    "unsupported_approval_action");
        }

        var approval = approvalService.approve(id, principal.userId(), principal.tenantId());

        // If this was a refund approval, execute the refund
        if ("refund".equals(approval.getAction())) {
            var refundResponse = refundOrchestration.executeApprovedRefund(approval);
            // B-022: stamp executed_at on the success path so the common case never enters the
            // reconciler. This is bound to the caller's tenant (the request already runs under
            // TenantContext) and is conditional on executed_at IS NULL, so it is consistent with
            // the reconciler's marker. If the gateway call above THREW, we never reach here and the
            // row stays APPROVED/executed_at NULL — exactly the stuck state the reconciler re-drives
            // (keyed on the same "refund-approval-<id>", which the PSP dedups). Additive: does not
            // change the no-double-pay proof (the PSP key is the money backstop, not this marker).
            if (refundResponse.isSuccessful()) {
                approvalService.markRefundExecuted(approval.getId(), principal.tenantId());
            }
            return ResponseEntity.ok(ResponseMapper.toRefundResponse(refundResponse));
        }

        return ResponseEntity.ok(ResponseMapper.toApprovalResponse(approval));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Reject a pending request")
    public ResponseEntity<ApprovalResponse> reject(
            @PathVariable String id,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        // GAP-068 review fix: the same action guard as approve(), mirrored. A b2b approval
        // (vendor_payment_approve / purchase_order_approve) rejected HERE would flip REJECTED
        // without emitting the b2b taxonomy events (VendorPaymentApprovalRejected /
        // PurchaseOrderApprovalRejected — B2bApprovalService.reviewReject is their only emitter),
        // silently bypassing the owning module's review path that GET /v1/approvals naturally leads
        // a reviewer to. Load tenant-checked (404-no-oracle) and 409 any non-refund action so b2b
        // rejections must go through POST /v1/b2b/approvals/{id}/reject.
        var pending = approvalService.findById(id)
                .filter(a -> principal.tenantId().equals(a.getTenantId()))
                .orElseThrow(() -> new ResourceNotFoundException("Approval not found"));
        if (!"refund".equals(pending.getAction())) {
            throw new ConflictException(
                    "Approval action '" + pending.getAction()
                            + "' must be reviewed via its owning module's approval endpoint",
                    "unsupported_approval_action");
        }

        var approval = approvalService.reject(id, principal.userId(), principal.tenantId());
        return ResponseEntity.ok(ResponseMapper.toApprovalResponse(approval));
    }
}
