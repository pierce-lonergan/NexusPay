package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.ApprovalResponse;
import io.nexuspay.gateway.adapter.in.rest.dto.CreateRefundRequest;
import io.nexuspay.gateway.adapter.in.rest.dto.RefundApiResponse;
import io.nexuspay.gateway.application.RefundOrchestrationService;
import io.nexuspay.gateway.application.RefundOrchestrationService.RefundResult;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.iam.domain.PendingApproval;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.domain.RefundResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * INT-2 Invariant 3: the refund response is self-describing.
 *
 * <ul>
 *   <li>202 (amount &gt;= threshold): body is an {@link ApprovalResponse} with
 *       {@code requires_approval=true}, {@code approval_threshold} = the configured threshold, and
 *       {@code id} = the pending-approval id.</li>
 *   <li>201 (amount &lt; threshold): body is a {@link RefundApiResponse} with
 *       {@code requires_approval=false}.</li>
 * </ul>
 *
 * <p>FAILS if the DTO/mapper change or the threshold wiring is reverted.</p>
 */
class PaymentControllerRefundApprovalTest {

    private static final long THRESHOLD = 50000L;

    private RefundOrchestrationService refundOrchestration;
    private PaymentController controller;
    private final NexusPayPrincipal operator =
            new NexusPayPrincipal("op_1", "t1", "operator", NexusPayPrincipal.AuthMethod.JWT);

    @BeforeEach
    void setUp() {
        var gateway = mock(PaymentGatewayPort.class);
        refundOrchestration = mock(RefundOrchestrationService.class);
        var screeningOrigins = mock(ScreeningOriginService.class);
        controller = new PaymentController(gateway, refundOrchestration, screeningOrigins);
        when(refundOrchestration.refundApprovalThreshold()).thenReturn(THRESHOLD);
    }

    @Test
    void aboveThreshold_returns202_withRequiresApprovalAndThreshold() {
        var approval = new PendingApproval("appr_99", "refund", "Payment", "pay_1",
                Map.of("payment_id", "pay_1", "amount", 60000L), "PENDING", "op_1", null,
                "t1", Instant.now(), null);
        when(refundOrchestration.createRefund(anyString(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(new RefundResult(null, approval));

        ResponseEntity<?> resp = controller.createRefund(
                "pay_1", new CreateRefundRequest(60000L, "USD", "big"), "idem-1", operator);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).isInstanceOf(ApprovalResponse.class);
        ApprovalResponse body = (ApprovalResponse) resp.getBody();
        assertThat(body.requires_approval()).isTrue();
        assertThat(body.approval_threshold()).isEqualTo(THRESHOLD);
        assertThat(body.id()).isEqualTo("appr_99");
    }

    @Test
    void belowThreshold_returns201_withRequiresApprovalFalse() {
        var refund = new RefundResponse("rfnd_1", "pay_1", "succeeded", 100L, "USD", "small",
                "stripe", "con", null, null, Instant.now());
        when(refundOrchestration.createRefund(anyString(), anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(new RefundResult(refund, null));

        ResponseEntity<?> resp = controller.createRefund(
                "pay_1", new CreateRefundRequest(100L, "USD", "small"), "idem-2", operator);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isInstanceOf(RefundApiResponse.class);
        RefundApiResponse body = (RefundApiResponse) resp.getBody();
        assertThat(body.requires_approval()).isFalse();
    }
}
