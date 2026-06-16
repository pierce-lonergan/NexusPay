package io.nexuspay.gateway.application;

import io.nexuspay.common.mode.PaymentMode;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.PendingApproval;
import io.nexuspay.payment.adapter.out.mock.MockPaymentGatewayPort;
import io.nexuspay.payment.application.port.PaymentGatewayPort;
import io.nexuspay.payment.application.screening.ScreeningOriginService;
import io.nexuspay.payment.domain.RefundRequest;
import io.nexuspay.payment.domain.RefundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orchestrates refund creation with maker-checker threshold check.
 * If amount >= threshold, creates a pending approval (returns 202).
 * Otherwise delegates directly to payment-orchestration.
 */
@Service
public class RefundOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RefundOrchestrationService.class);

    /**
     * INT-3: the originating payment's mode, captured into the approval payload at refund-approval
     * creation so the DEFERRED executor (approver/reconciler thread, where the original test key's
     * {@code PaymentMode} is gone) can re-apply it before calling the gateway. Belt-and-suspenders
     * alongside the gateway's {@code pay_test_} id fail-safe.
     */
    private static final String PAYLOAD_IS_LIVE = "is_live";

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
            // INT-3: capture the originating payment's mode into the payload so the deferred executor
            // (approver/reconciler) re-applies it. A mock payment id (pay_test_*) is a TEST payment; any
            // other id is treated as LIVE. The gateway's id fail-safe still independently routes a
            // pay_test_* refund to the mock even if this marker were ever absent.
            boolean originatingLive = !isTestPaymentId(paymentId);
            var payload = new LinkedHashMap<String, Object>();
            payload.put("payment_id", paymentId);
            payload.put("amount", amount);
            payload.put("currency", currency != null ? currency : "");
            payload.put("reason", reason != null ? reason : "");
            payload.put(PAYLOAD_IS_LIVE, originatingLive);
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
     *
     * <p>INT-3: this runs on a DEFERRED context — the approver's request thread (a console/OIDC actor
     * defaults to LIVE) or the {@code RefundReconciler}'s SYSTEM thread (mode UNSET → resolves LIVE). On
     * neither does the originating test key's {@code PaymentMode} survive. We RE-APPLY the originating
     * payment's mode (captured into the approval payload at creation) for the duration of the gateway call
     * so a TEST refund routes to the mock, then restore the prior holder state in a finally so we never
     * leak a mode onto the executing thread. The gateway's {@code pay_test_} id fail-safe is the
     * independent backstop if this marker is ever missing.</p>
     */
    public RefundResponse executeApprovedRefund(PendingApproval approval) {
        var payload = approval.getPayload();
        String idempotencyKey = "refund-approval-" + approval.getId();
        RefundRequest refundRequest = new RefundRequest(
                (String) payload.get("payment_id"),
                ((Number) payload.get("amount")).longValue(),
                (String) payload.get("currency"),
                (String) payload.get("reason"),
                idempotencyKey
        );

        // Re-derive the originating mode: prefer the persisted payload marker; fall back to the id prefix
        // for legacy approvals created before the marker existed (both agree for a mock-created payment).
        Boolean payloadLive = payload.get(PAYLOAD_IS_LIVE) instanceof Boolean b ? b : null;
        boolean originatingLive = payloadLive != null
                ? payloadLive
                : !isTestPaymentId(refundRequest.paymentId());

        // Snapshot + restore the holder so the deferred thread (request OR system) is left exactly as we
        // found it — clear() if it was unset (system/reconciler), restore the prior boolean otherwise.
        boolean priorUnset = PaymentMode.isUnset();
        boolean priorLive = PaymentMode.isLiveExplicit();
        PaymentMode.set(originatingLive);
        try {
            return paymentGatewayPort.createRefund(refundRequest);
        } finally {
            if (priorUnset) {
                PaymentMode.clear();
            } else {
                PaymentMode.set(priorLive);
            }
        }
    }

    /** A mock-minted (TEST) payment id (Stripe-style {@code pay_test_*}). */
    private static boolean isTestPaymentId(String paymentId) {
        return paymentId != null && paymentId.startsWith(MockPaymentGatewayPort.PAY_PREFIX);
    }
}
