package io.nexuspay.gateway.application;

import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.PendingApproval;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
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
    private final ScreeningOriginService screeningOrigins;
    private final long refundApprovalThreshold;

    public RefundOrchestrationService(PaymentGatewayPort paymentGatewayPort,
                                       ApprovalService approvalService,
                                       ScreeningOriginService screeningOrigins,
                                       @Value("${nexuspay.iam.refund-approval-threshold:50000}") long refundApprovalThreshold) {
        this.paymentGatewayPort = paymentGatewayPort;
        this.approvalService = approvalService;
        this.screeningOrigins = screeningOrigins;
        this.refundApprovalThreshold = refundApprovalThreshold;
    }

    public record RefundResult(RefundResponse refundResponse, PendingApproval pendingApproval) {
        public boolean requiresApproval() {
            return pendingApproval != null;
        }
    }

    /**
     * The maker-checker refund approval threshold (minor units). INT-2: the controller stamps this onto
     * the 202 approval-required response so consumers can surface it. No logic change.
     */
    public long refundApprovalThreshold() {
        return refundApprovalThreshold;
    }

    public RefundResult createRefund(String paymentId, long amount, String currency,
                                      String reason, String idempotencyKey,
                                      String userId, String tenantId) {
        // SEC-07 (B-007): assert tenant ownership FIRST, before the amount/threshold branch. This closes
        // BOTH the maker-checker (>= threshold) path AND the sub-threshold direct-to-PSP path — without
        // it a tenant-A operator could refund tenant-B funds with amount=49999 (below the approval
        // threshold). Fail-closed (404) on absent origin or tenant mismatch.
        screeningOrigins.assertOwnedBy(paymentId, tenantId);
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
     * Executes a previously approved refund. The idempotency key is derived
     * deterministically from the approval id, so if this refund is ever
     * submitted twice (a retry, a double-click, or two replicas racing the same
     * approval) HyperSwitch dedups it to a single refund (B-009).
     */
    public RefundResponse executeApprovedRefund(PendingApproval approval) {
        var payload = approval.getPayload();
        String idempotencyKey = "refund-approval-" + approval.getId();
        return paymentGatewayPort.createRefund(new RefundRequest(
                (String) payload.get("payment_id"),
                ((Number) payload.get("amount")).longValue(),
                (String) payload.get("currency"),
                (String) payload.get("reason"),
                idempotencyKey
        ));
    }
}
