package io.nexuspay.gateway.adapter.in.rest;

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

    private static String mapApprovalStatus(String status) {
        return switch (status) {
            case "PENDING" -> "pending_approval";
            case "APPROVED" -> "approved";
            case "REJECTED" -> "rejected";
            default -> status.toLowerCase();
        };
    }
}
