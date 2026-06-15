package io.nexuspay.gateway.adapter.in.rest;

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
    @PreAuthorize("hasRole('admin')")
    @Operation(summary = "Approve a pending request and execute the action")
    public ResponseEntity<?> approve(
            @PathVariable String id,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
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
        var approval = approvalService.reject(id, principal.userId(), principal.tenantId());
        return ResponseEntity.ok(ResponseMapper.toApprovalResponse(approval));
    }
}
