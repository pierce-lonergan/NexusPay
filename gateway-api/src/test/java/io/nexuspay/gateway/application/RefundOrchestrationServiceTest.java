package io.nexuspay.gateway.application;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.PendingApproval;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private ScreeningOriginService screeningOrigins;
    private RefundOrchestrationService svc;

    @BeforeEach
    void setUp() {
        gateway = mock(PaymentGatewayPort.class);
        approvals = mock(ApprovalService.class);
        screeningOrigins = mock(ScreeningOriginService.class);
        svc = new RefundOrchestrationService(gateway, approvals, screeningOrigins, 50000L);
        // SEC-07 (B-007): by default the caller (tenant "t1") owns pay_1, so the ownership assertion
        // passes and the existing idempotency-key assertions run. The cross-tenant tests below override
        // this to make assertOwnedBy throw (proving the fail-closed 404).
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
    void exposesConfiguredApprovalThreshold() {
        // INT-2 Invariant 3: the controller stamps this onto the 202 approval-required response.
        assertThat(svc.refundApprovalThreshold()).isEqualTo(50000L);
    }

    @Test
    void belowThresholdRefundsDirectlyWithCallerIdempotencyKey() {
        var result = svc.createRefund("pay_1", 100L, "USD", "small", "client-key", "maker", "t1");

        ArgumentCaptor<RefundRequest> cap = ArgumentCaptor.forClass(RefundRequest.class);
        verify(gateway).createRefund(cap.capture());
        assertThat(cap.getValue().idempotencyKey()).isEqualTo("client-key");
        assertThat(result.requiresApproval()).isFalse();
    }

    // ---- SEC-07 (B-007): cross-tenant ownership gate on createRefund ----

    /**
     * SEC-07 (B-007): a tenant-A operator must NOT be able to refund a tenant-B-owned payment with an
     * amount BELOW the approval threshold (the path that previously routed straight to the PSP with no
     * ownership check — amount=49999 < 50000). The ownership assertion runs FIRST and 404s before any
     * PSP call. This test FAILS if the assertOwnedBy call is removed from the top of createRefund.
     */
    @Test
    void subThresholdCrossTenantRefund_isRejected_neverReachesPsp() {
        // assertOwnedBy throws for the cross-tenant id (mirrors the real fail-closed behavior).
        org.mockito.Mockito.doThrow(ResourceNotFoundException.of("Payment", "pay_b"))
                .when(screeningOrigins).assertOwnedBy("pay_b", "tenant-A");

        assertThatThrownBy(() ->
                svc.createRefund("pay_b", 49999L, "USD", "sneaky", "client-key", "operatorA", "tenant-A"))
                .isInstanceOf(ResourceNotFoundException.class);

        // The sub-threshold direct-to-PSP path must NOT have fired.
        verify(gateway, never()).createRefund(any());
    }

    /**
     * SEC-07 (B-007): the ABOVE-threshold (maker-checker) path is also gated — a cross-tenant refund
     * must 404 before any approval is created. Fails if assertOwnedBy is removed.
     */
    @Test
    void aboveThresholdCrossTenantRefund_isRejected_neverCreatesApproval() {
        org.mockito.Mockito.doThrow(ResourceNotFoundException.of("Payment", "pay_b"))
                .when(screeningOrigins).assertOwnedBy("pay_b", "tenant-A");

        assertThatThrownBy(() ->
                svc.createRefund("pay_b", 60000L, "USD", "big", "client-key", "operatorA", "tenant-A"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(approvals, never()).createApproval(any(), any(), any(), any(), any(), any());
        verify(gateway, never()).createRefund(any());
    }
}
