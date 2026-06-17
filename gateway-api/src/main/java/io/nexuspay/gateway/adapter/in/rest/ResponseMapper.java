package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.common.domain.ApiError;
import io.nexuspay.gateway.adapter.in.rest.dto.*;
import io.nexuspay.gateway.adapter.out.persistence.WebhookDeliveryEntity;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import io.nexuspay.gateway.domain.PaymentSession;
import io.nexuspay.gateway.domain.PaymentToken;
import io.nexuspay.iam.domain.PendingApproval;
import io.nexuspay.ledger.domain.JournalEntry;
import io.nexuspay.ledger.domain.LedgerAccount;
import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundResponse;

import java.util.Map;

/**
 * Maps domain/module objects to gateway API DTOs.
 */
final class ResponseMapper {

    private ResponseMapper() {
    }

    /**
     * Maps a payment with no explicit mode (mode dropped by NON_NULL). Retained for any caller without
     * an authenticated principal; the REST endpoints use {@link #toPaymentResponse(PaymentResponse,
     * String)} to stamp the server-derived mode.
     */
    static PaymentApiResponse toPaymentResponse(PaymentResponse p) {
        return toPaymentResponse(p, null);
    }

    /**
     * INT-3: maps a payment and stamps the SERVER-DERIVED key {@code mode} ("test"/"live"). The caller
     * derives {@code mode} from the authenticated principal's {@code live()} flag, never from the request
     * body. A {@code null} mode is dropped by the DTO's NON_NULL include.
     */
    static PaymentApiResponse toPaymentResponse(PaymentResponse p, String mode) {
        return new PaymentApiResponse(
                p.gatewayPaymentId(), p.status(), p.amount(), p.currency(),
                p.captureMethod(), p.customerId(), p.connectorName(),
                p.errorCode(), p.errorMessage(), p.createdAt(), p.metadata(), mode
        );
    }

    static RefundApiResponse toRefundResponse(RefundResponse r) {
        return new RefundApiResponse(
                r.gatewayRefundId(), r.paymentId(), r.status(), r.amount(),
                r.currency(), r.reason(), r.connectorName(),
                r.errorCode(), r.errorMessage(), r.createdAt(),
                // INT-2 Invariant 3: a created refund is self-describing — it did NOT require approval.
                Boolean.FALSE
        );
    }

    /**
     * Approval-lifecycle mapping (list/approve/reject). The {@code requires_approval}/
     * {@code approval_threshold} fields are left null (dropped by NON_NULL); they are only stamped on
     * the refund-creation 202 path via {@link #toApprovalResponse(PendingApproval, long)}.
     */
    static ApprovalResponse toApprovalResponse(PendingApproval a) {
        return new ApprovalResponse(
                a.getId(), a.getAction(), a.getResourceType(), a.getResourceId(),
                mapApprovalStatus(a.getStatus()), a.getRequestedBy(), a.getReviewedBy(),
                a.getPayload(), a.getCreatedAt(), a.getReviewedAt(),
                null, null
        );
    }

    /**
     * INT-2 Invariant 3: refund-creation 202 path. Stamps {@code requires_approval=true} and the
     * configured maker-checker {@code approval_threshold} (minor units). The pending-approval id is the
     * existing {@code id} field — consumers read {@code id} for the approval id.
     */
    static ApprovalResponse toApprovalResponse(PendingApproval a, long approvalThreshold) {
        return new ApprovalResponse(
                a.getId(), a.getAction(), a.getResourceType(), a.getResourceId(),
                mapApprovalStatus(a.getStatus()), a.getRequestedBy(), a.getReviewedBy(),
                a.getPayload(), a.getCreatedAt(), a.getReviewedAt(),
                Boolean.TRUE, approvalThreshold
        );
    }

    static LedgerAccountResponse toLedgerAccountResponse(LedgerAccount la) {
        return new LedgerAccountResponse(
                la.getId(), la.getName(), la.getType().name(), la.getCurrency(),
                la.getPostedBalance(), la.getCreatedAt(), la.getUpdatedAt()
        );
    }

    static JournalEntryResponse toJournalEntryResponse(JournalEntry je) {
        var postings = je.getPostings().stream()
                .map(p -> new JournalEntryResponse.PostingResponse(
                        p.id(), p.ledgerAccountId(), p.amount(), p.currency()))
                .toList();
        return new JournalEntryResponse(
                je.getId(), je.getPaymentReference(), je.getDescription(),
                je.getPostedAt(), je.getMetadata(), postings
        );
    }

    static WebhookEndpointResponse toWebhookEndpointResponse(WebhookEndpointEntity e, boolean includeSecret) {
        return new WebhookEndpointResponse(
                e.getId(), e.getUrl(), e.getDescription(),
                includeSecret ? e.getSecret() : null,
                e.getEvents(), e.isEnabled(), e.getCreatedAt()
        );
    }

    /**
     * INT-4: maps a delivery row to its list/replay DTO. No secret (not a column on this entity) and no
     * canonical body (kept off the API) — leak-by-construction-impossible.
     */
    static WebhookDeliveryResponse toWebhookDeliveryResponse(WebhookDeliveryEntity d) {
        return new WebhookDeliveryResponse(
                d.getId(), d.getEndpointId(), d.getEventId(), d.getEventType(),
                d.getStatus().name(), d.getAttemptCount(), d.getMaxAttempts(),
                d.getLastStatusCode(), d.getLastError(), d.getNextAttemptAt(),
                d.getCreatedAt(), d.getUpdatedAt(), d.getDeliveredAt()
        );
    }

    // --- Payment Session / Token mappers (Sprint 3.5) ---

    static PaymentSessionResponse toPaymentSessionResponse(PaymentSession s, String clientSecret) {
        return new PaymentSessionResponse(
                s.getId(), s.getStatus(), s.getAmount(), s.getCurrency(),
                s.getCustomerId(), s.getPaymentIntentId(), clientSecret,
                s.getAllowedPaymentMethods(), s.getSuccessUrl(), s.getCancelUrl(),
                s.getBranding(), s.getExpiresAt(), s.getCreatedAt()
        );
    }

    static TokenizeResponse toTokenizeResponse(PaymentToken t) {
        return new TokenizeResponse(
                t.getId(), t.getType(), t.getCardLastFour(),
                t.getCardBrand(), t.getExpiresAt()
        );
    }

    static SessionStatusResponse toSessionStatusResponse(PaymentSession s) {
        return new SessionStatusResponse(
                s.getId(), s.getStatus(), s.getAmount(), s.getCurrency(),
                s.getPaymentIntentId(), s.getAllowedPaymentMethods(), null
        );
    }

    /**
     * INT-6: maps a gateway {@link PaymentResponse} to the SDK confirm result the {@code @nexus-pay/js}
     * client consumes. The {@code status} is DERIVED from the gateway payment status (never the session
     * status, which is always {@code "complete"} and meaningless to {@code confirm()}):
     *
     * <ul>
     *   <li>{@code succeeded} &rarr; {@code "succeeded"} — captured, terminal money state;</li>
     *   <li>{@code failed}/{@code cancelled} &rarr; {@code "failed"} (carries {@code error});</li>
     *   <li>{@code requires_capture} (a fraud/compliance HOLD), {@code requires_action} (3DS),
     *       {@code requires_confirmation}, {@code requires_payment_method} &rarr; {@code "requires_action"} —
     *       a held payment is NEVER reported as {@code succeeded};</li>
     *   <li>{@code processing} &rarr; {@code "processing"};</li>
     *   <li>anything else / null &rarr; {@code "processing"} (fail-safe — never default to
     *       {@code "succeeded"}).</li>
     * </ul>
     *
     * <p>{@code nextAction} is derived from the intent metadata's {@code next_action} ({@code {type,url}})
     * and populated ONLY for {@code requires_action}; {@code error} is built from the payment's
     * {@code errorCode}/{@code errorMessage} and populated ONLY for {@code failed}. {@code mode} is the
     * SERVER-DERIVED key mode ("test"/"live"); {@code livemode} mirrors it.
     */
    static ConfirmResponse toConfirmResponse(PaymentResponse p, String mode) {
        String raw = p.status() == null ? "" : p.status();
        String confirmStatus = switch (raw) {
            case PaymentResponse.STATUS_SUCCEEDED -> "succeeded";
            case PaymentResponse.STATUS_FAILED, PaymentResponse.STATUS_CANCELLED -> "failed";
            // PaymentResponse has no STATUS_REQUIRES_ACTION constant (HyperSwitch surfaces the raw
            // "requires_action" string for a 3DS/next-action intent), so it is matched as a literal here
            // alongside the held/confirmation/payment-method states.
            case PaymentResponse.STATUS_REQUIRES_CAPTURE,
                 "requires_action",
                 PaymentResponse.STATUS_REQUIRES_CONFIRMATION,
                 PaymentResponse.STATUS_REQUIRES_PAYMENT_METHOD -> "requires_action";
            case PaymentResponse.STATUS_PROCESSING -> "processing";
            // Fail-safe: an unknown/null gateway status is NEVER reported as succeeded (Invariant 2).
            default -> "processing";
        };

        ConfirmResponse.NextAction nextAction = "requires_action".equals(confirmStatus)
                ? nextActionFromMetadata(p.metadata())
                : null;
        ConfirmResponse.ConfirmError error = "failed".equals(confirmStatus)
                ? new ConfirmResponse.ConfirmError(
                        ApiError.TYPE_PAYMENT,
                        p.errorCode() != null ? p.errorCode() : "payment_failed",
                        p.errorMessage() != null ? p.errorMessage() : "Payment failed")
                : null;

        boolean livemode = "live".equals(mode);
        return new ConfirmResponse(confirmStatus, p.gatewayPaymentId(), mode, livemode, nextAction, error);
    }

    /**
     * INT-6: derives the 3DS / redirect {@code nextAction} from the intent metadata. The domain
     * {@link PaymentResponse} has no dedicated {@code next_action} field, but a live HyperSwitch intent
     * surfaces a {@code next_action} map ({@code {type,url}}) in the metadata blob. Defensive: returns
     * {@code null} when absent or shaped unexpectedly (the held {@code requires_capture} case has no
     * redirect — the client shows "under review" from the status alone).
     */
    private static ConfirmResponse.NextAction nextActionFromMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object raw = metadata.get("next_action");
        if (!(raw instanceof Map<?, ?> na)) {
            return null;
        }
        Object type = na.get("type");
        Object url = na.get("url");
        if (type == null && url == null) {
            return null;
        }
        return new ConfirmResponse.NextAction(
                type != null ? String.valueOf(type) : null,
                url != null ? String.valueOf(url) : null);
    }

    private static String mapApprovalStatus(String status) {
        return switch (status) {
            case "PENDING" -> "pending_approval";
            case "APPROVED" -> "approved";
            case "REJECTED" -> "rejected";
            default -> status.toLowerCase();
        };
    }
}
