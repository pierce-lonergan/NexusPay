package io.nexuspay.gateway.application;

import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.PendingApproval;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-009: an approved refund must carry a deterministic idempotency key derived
 * from the approval id, so a retry/double-click/replica race dedups at the gateway.
 */
class RefundOrchestrationServiceTest {

    private PaymentGatewayPort gateway;
    private ApprovalService approvals;
    private RefundOrchestrationService svc;

    @BeforeEach
    void setUp() {
        gateway = mock(PaymentGatewayPort.class);
        approvals = mock(ApprovalService.class);
        svc = new RefundOrchestrationService(gateway, approvals, 50000L);
    }

    private PendingApproval approval(String id) {
        return new PendingApproval(id, "refund", "Payment", "pay_1",
                Map.of("payment_id", "pay_1", "amount", 60000L, "currency", "USD", "reason", "approved"),
                "APPROVED", "maker", "checker", "t1", Instant.now(), Instant.now());
    }

    @Test
    void executeApprovedRefundUsesDeterministicIdempotencyKeyFromApprovalId() {
        svc.executeApprovedRefund(approval("appr_42"));

        ArgumentCaptor<RefundRequest> cap = ArgumentCaptor.forClass(RefundRequest.class);
        verify(gateway).createRefund(cap.capture());
        assertThat(cap.getValue().idempotencyKey())
                .as("approved refund must be idempotent on the approval id")
                .isEqualTo("refund-approval-appr_42");
        assertThat(cap.getValue().paymentId()).isEqualTo("pay_1");
        assertThat(cap.getValue().amount()).isEqualTo(60000L);
    }

    @Test
    void aboveThresholdCreatesApprovalInsteadOfRefunding() {
        when(approvals.createApproval(any(), any(), any(), any(), any(), any()))
                .thenReturn(approval("appr_new"));

        var result = svc.createRefund("pay_1", 60000L, "USD", "big", "client-key", "maker", "t1");

        assertThat(result.requiresApproval()).isTrue();
        verify(gateway, never()).createRefund(any());
    }

    @Test
    void belowThresholdRefundsDirectlyWithCallerIdempotencyKey() {
        var result = svc.createRefund("pay_1", 100L, "USD", "small", "client-key", "maker", "t1");

        ArgumentCaptor<RefundRequest> cap = ArgumentCaptor.forClass(RefundRequest.class);
        verify(gateway).createRefund(cap.capture());
        assertThat(cap.getValue().idempotencyKey()).isEqualTo("client-key");
        assertThat(result.requiresApproval()).isFalse();
    }
}
