package io.nexuspay.gateway.adapter.in.rest;

import io.nexuspay.gateway.adapter.in.rest.dto.*;
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

    static PaymentApiResponse toPaymentResponse(PaymentResponse p) {
        return new PaymentApiResponse(
                p.gatewayPaymentId(), p.status(), p.amount(), p.currency(),
                p.captureMethod(), p.customerId(), p.connectorName(),
                p.errorCode(), p.errorMessage(), p.createdAt(), p.metadata()
        );
    }

    static RefundApiResponse toRefundResponse(RefundResponse r) {
        return new RefundApiResponse(
                r.gatewayRefundId(), r.paymentId(), r.status(), r.amount(),
                r.currency(), r.reason(), r.connectorName(),
                r.errorCode(), r.errorMessage(), r.createdAt()
        );
    }

    static ApprovalResponse toApprovalResponse(PendingApproval a) {
        return new ApprovalResponse(
                a.getId(), a.getAction(), a.getResourceType(), a.getResourceId(),
                mapApprovalStatus(a.getStatus()), a.getRequestedBy(), a.getReviewedBy(),
                a.getPayload(), a.getCreatedAt(), a.getReviewedAt()
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
