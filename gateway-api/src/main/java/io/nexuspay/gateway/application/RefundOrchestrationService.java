package io.nexuspay.gateway.application;

import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.PendingApproval;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Orchestrates refund creation with maker-checker threshold check.
 * If amount >= threshold, creates a pending approval (returns 202).
 * Otherwise delegates directly to payment-orchestration.
 */
@Service
public class RefundOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RefundOrchestrationService.class);

    private final PaymentGatewayPort paymentGatewayPort;
    private final ApprovalService approvalService;
    private final long refundApprovalThreshold;

    public RefundOrchestrationService(PaymentGatewayPort paymentGatewayPort,
                                       ApprovalService approvalService,
                                       @Value("${nexuspay.iam.refund-approval-threshold:50000}") long refundApprovalThreshold) {
        this.paymentGatewayPort = paymentGatewayPort;
        this.approvalService = approvalService;
        this.refundApprovalThreshold = refundApprovalThreshold;
    }

    public record RefundResult(RefundResponse refundResponse, PendingApproval pendingApproval) {
        public boolean requiresApproval() {
            return pendingApproval != null;
        }
    }

    public RefundResult createRefund(String paymentId, long amount, String currency,
                                      String reason, String idempotencyKey,
                                      String userId, String tenantId) {
        if (amount >= refundApprovalThreshold) {
            log.info("Refund amount {} >= threshold {}, creating approval for payment {}",
                    amount, refundApprovalThreshold, paymentId);
            var payload = Map.<String, Object>of(
                    "payment_id", paymentId,
                    "amount", amount,
                    "currency", currency != null ? currency : "",
                    "reason", reason != null ? reason : ""
            );
            var approval = approvalService.createApproval(
                    "refund", "Payment", paymentId, payload, userId, tenantId);
            return new RefundResult(null, approval);
        }

        var refundResponse = paymentGatewayPort.createRefund(
                new RefundRequest(paymentId, amount, currency, reason, idempotencyKey));
        return new RefundResult(refundResponse, null);
    }

    /**
     * Executes a previously approved refund.
     */
    public RefundResponse executeApprovedRefund(PendingApproval approval) {
        var payload = approval.getPayload();
        return paymentGatewayPort.createRefund(new RefundRequest(
                (String) payload.get("payment_id"),
                ((Number) payload.get("amount")).longValue(),
                (String) payload.get("currency"),
                (String) payload.get("reason"),
                null
        ));
    }
}
