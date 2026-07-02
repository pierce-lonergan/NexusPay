package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.exception.ConflictException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.iam.domain.PendingApproval;
import io.nexuspay.payment.domain.RefundResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-022: original-path hardening. On a SUCCESSFUL approve-and-execute, the controller stamps
 * executed_at (bound to the caller's tenant) so the common case never enters the reconciler. On a
 * NON-success gateway response it must NOT mark the row, leaving it for the reconciler to re-drive.
 */
class ApprovalControllerTest {

    private ApprovalService approvalService;
    private RefundOrchestrationService refundOrchestration;
    private ApprovalController controller;
    private final NexusPayPrincipal admin =
            new NexusPayPrincipal("admin_user", "t1", "admin", NexusPayPrincipal.AuthMethod.JWT);

    @BeforeEach
    void setUp() {
        approvalService = mock(ApprovalService.class);
        refundOrchestration = mock(RefundOrchestrationService.class);
        controller = new ApprovalController(approvalService, refundOrchestration);
    }

    private PendingApproval refundApproval() {
        return new PendingApproval("appr_1", "refund", "Payment", "pay_1",
                Map.of("payment_id", "pay_1", "amount", 60000L), "APPROVED", "maker", "admin_user",
                "t1", Instant.now(), Instant.now());
    }

    private RefundResponse refund(String status) {
        return new RefundResponse("rfnd_1", "pay_1", status, 60000L, "USD", "r",
                "stripe", "con", null, null, Instant.now());
    }

    private PendingApproval b2bApproval(String status) {
        return new PendingApproval("appr_b2b", "vendor_payment_approve", "VendorPayment", "vp_1",
                Map.of("payment_id", "vp_1", "amount", 60000L), status, "maker", null,
                "t1", Instant.now(), null);
    }

    @Test
    void approveSuccess_stampsExecutedAtBoundToCallerTenant() {
        when(approvalService.findById("appr_1")).thenReturn(Optional.of(refundApproval()));
        when(approvalService.approve("appr_1", "admin_user", "t1")).thenReturn(refundApproval());
        when(refundOrchestration.executeApprovedRefund(any(PendingApproval.class)))
                .thenReturn(refund(RefundResponse.STATUS_SUCCEEDED));

        controller.approve("appr_1", admin);

        verify(approvalService).markRefundExecuted(eq("appr_1"), eq("t1"));
    }

    @Test
    void approveWithPendingGatewayResponse_doesNotMarkExecuted() {
        when(approvalService.findById("appr_1")).thenReturn(Optional.of(refundApproval()));
        when(approvalService.approve("appr_1", "admin_user", "t1")).thenReturn(refundApproval());
        when(refundOrchestration.executeApprovedRefund(any(PendingApproval.class)))
                .thenReturn(refund(RefundResponse.STATUS_PENDING));

        controller.approve("appr_1", admin);

        verify(approvalService, never()).markRefundExecuted(eq("appr_1"), eq("t1"));
    }

    // ---- GAP-068: fail-closed stranding guard on the generic approve endpoint ----------------------

    @Test
    void approveNonRefundAction_returns409_beforeClaiming_approvalStaysPending() {
        // A b2b approval (vendor_payment_approve) must NOT be claimable here: flipping it APPROVED
        // without executing would strand it forever (the B-022 reconciler only re-drives refunds).
        // The guard 409s BEFORE the claim, so the row remains PENDING for the owning module's endpoint.
        when(approvalService.findById("appr_b2b")).thenReturn(Optional.of(b2bApproval("PENDING")));

        assertThrows(ConflictException.class, () -> controller.approve("appr_b2b", admin));

        verify(approvalService, never()).approve(anyString(), anyString(), anyString());
        verify(approvalService, never()).markRefundExecuted(anyString(), anyString());
    }

    @Test
    void rejectNonRefundAction_returns409_rejectNeverInvoked_rowStaysPending() {
        // WAVE1 review fix: the reject endpoint carries the SAME action guard as approve. A b2b
        // approval rejected here would skip the b2b taxonomy events (B2bApprovalService.reviewReject
        // is their only emitter) — it must be 409'd toward POST /v1/b2b/approvals/{id}/reject.
        when(approvalService.findById("appr_b2b")).thenReturn(Optional.of(b2bApproval("PENDING")));

        assertThrows(ConflictException.class, () -> controller.reject("appr_b2b", admin));

        verify(approvalService, never()).reject(anyString(), anyString(), anyString());
    }

    @Test
    void rejectCrossTenantApproval_returns404_noOracle_rejectNeverInvoked() {
        var foreign = new PendingApproval("appr_y", "refund", "Payment", "pay_9",
                Map.of("payment_id", "pay_9", "amount", 60000L), "PENDING", "maker", null,
                "OTHER-TENANT", Instant.now(), null);
        when(approvalService.findById("appr_y")).thenReturn(Optional.of(foreign));

        assertThrows(ResourceNotFoundException.class, () -> controller.reject("appr_y", admin));

        verify(approvalService, never()).reject(anyString(), anyString(), anyString());
    }

    @Test
    void rejectRefundAction_passesTheGuard_andRejects() {
        var refund = new PendingApproval("appr_r", "refund", "Payment", "pay_1",
                Map.of("payment_id", "pay_1"), "PENDING", "maker", null,
                "t1", Instant.now(), null);
        when(approvalService.findById("appr_r")).thenReturn(Optional.of(refund));
        when(approvalService.reject("appr_r", "admin_user", "t1")).thenReturn(refund);

        controller.reject("appr_r", admin);

        verify(approvalService).reject("appr_r", "admin_user", "t1");
    }

    @Test
    void approveCrossTenantApproval_returns404_noOracle_beforeClaiming() {
        // The pre-claim load is tenant-checked: a foreign tenant's approval id is indistinguishable
        // from a non-existent one (404), and the claim is never attempted.
        var foreign = new PendingApproval("appr_x", "refund", "Payment", "pay_9",
                Map.of("payment_id", "pay_9", "amount", 60000L), "PENDING", "maker", null,
                "OTHER-TENANT", Instant.now(), null);
        when(approvalService.findById("appr_x")).thenReturn(Optional.of(foreign));

        assertThrows(ResourceNotFoundException.class, () -> controller.approve("appr_x", admin));

        verify(approvalService, never()).approve(anyString(), anyString(), anyString());
    }
}
