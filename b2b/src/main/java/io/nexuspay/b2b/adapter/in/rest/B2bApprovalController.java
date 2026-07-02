package io.nexuspay.b2b.adapter.in.rest;

import io.nexuspay.b2b.adapter.in.rest.dto.B2bApprovalPendingResponse;
import io.nexuspay.b2b.application.service.B2bApprovalService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GAP-068: the b2b maker-checker REVIEW endpoints — the gateway {@code ApprovalController} mirror
 * for the b2b-owned actions ({@code vendor_payment_approve} / {@code purchase_order_approve}).
 * Admin-only. The reviewer identity comes from the authenticated {@link NexusPayPrincipal}
 * (fail-closed 403 when absent); {@code B2bApprovalService} enforces tenant scoping (404, no
 * oracle), creator != approver, requester != reviewer, and execute-once.
 *
 * @since GAP-068 (WAVE1-money-ledger)
 */
@RestController
@RequestMapping("/v1/b2b/approvals")
public class B2bApprovalController {

    private final B2bApprovalService approvalService;

    public B2bApprovalController(B2bApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<?> approve(
            @PathVariable String id,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        requirePrincipal(principal);
        var result = approvalService.reviewApprove(id, principal.userId(), principal.tenantId());
        // Return the EXECUTED resource state, in the owning endpoint's exact response shape.
        if (result.vendorPayment() != null) {
            return ResponseEntity.ok(VendorPaymentController.toResponse(result.vendorPayment()));
        }
        return ResponseEntity.ok(PurchaseOrderController.toResponse(result.purchaseOrder()));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<B2bApprovalPendingResponse> reject(
            @PathVariable String id,
            @AuthenticationPrincipal NexusPayPrincipal principal) {
        requirePrincipal(principal);
        var rejected = approvalService.reviewReject(id, principal.userId(), principal.tenantId());
        return ResponseEntity.ok(new B2bApprovalPendingResponse(
                null, rejected.getId(), "rejected", rejected.getAction(),
                rejected.getResourceId(), null));
    }

    /** GAP-068 FAIL-CLOSED: reviewing money movement requires an identifiable reviewer. */
    private static void requirePrincipal(NexusPayPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "review requires an identifiable principal");
        }
    }
}
