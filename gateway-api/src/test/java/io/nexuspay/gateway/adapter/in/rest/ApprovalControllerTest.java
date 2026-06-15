package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.iam.domain.PendingApproval;
import io.nexuspay.payment.domain.RefundResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void approveSuccess_stampsExecutedAtBoundToCallerTenant() {
        when(approvalService.approve("appr_1", "admin_user", "t1")).thenReturn(refundApproval());
        when(refundOrchestration.executeApprovedRefund(any(PendingApproval.class)))
                .thenReturn(refund(RefundResponse.STATUS_SUCCEEDED));

        controller.approve("appr_1", admin);

        verify(approvalService).markRefundExecuted(eq("appr_1"), eq("t1"));
    }

    @Test
    void approveWithPendingGatewayResponse_doesNotMarkExecuted() {
        when(approvalService.approve("appr_1", "admin_user", "t1")).thenReturn(refundApproval());
        when(refundOrchestration.executeApprovedRefund(any(PendingApproval.class)))
                .thenReturn(refund(RefundResponse.STATUS_PENDING));

        controller.approve("appr_1", admin);

        verify(approvalService, never()).markRefundExecuted(eq("appr_1"), eq("t1"));
    }
}
