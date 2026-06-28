package io.nexuspay.payment.domain.projection;

import io.nexuspay.payment.domain.PaymentResponse;
import io.nexuspay.payment.domain.RefundResponse;

/**
 * GAP-076 (critique v3 F1): the WRITE-TIME monotonic status-precedence rank for the read-model upsert.
 *
 * <p>The cardinal rule forbids reading the projection to reconcile, so the only safe ordering control is
 * a write-time monotonic rank rather than a read-back compare. An upsert accepts a status advance ONLY
 * when {@code rank(new) >= rank(existing)}, and a TERMINAL status is never overwritten by a different
 * status. This makes the sync(create)+webhook(settlement) race and any webhook reordering safe: a
 * late-arriving {@code processing} cannot regress a {@code succeeded} row, and the FIRST terminal that
 * lands wins (succeeded/failed/cancelled are mutually-exclusive-and-final per payment lifecycle).</p>
 *
 * <p><b>Rank table (payment):</b>
 * <pre>
 *   requires_payment_method / requires_confirmation        = 0
 *   requires_action / processing                           = 1
 *   requires_capture                                       = 2
 *   succeeded / failed / cancelled (TERMINAL)              = 3
 *   (unknown)                                              = 0  (never beats a known non-zero state)
 * </pre>
 * <b>Rank table (refund):</b> {@code pending = 0}, {@code succeeded / failed (TERMINAL) = 1}.</p>
 */
public final class ProjectionStatusPrecedence {

    private ProjectionStatusPrecedence() {
    }

    /** Monotonic rank for a payment status; higher = further along the lifecycle. */
    public static int paymentRank(String status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case PaymentResponse.STATUS_REQUIRES_PAYMENT_METHOD,
                 PaymentResponse.STATUS_REQUIRES_CONFIRMATION -> 0;
            case PaymentResponse.STATUS_REQUIRES_ACTION,
                 PaymentResponse.STATUS_PROCESSING -> 1;
            case PaymentResponse.STATUS_REQUIRES_CAPTURE -> 2;
            case PaymentResponse.STATUS_SUCCEEDED,
                 PaymentResponse.STATUS_FAILED,
                 PaymentResponse.STATUS_CANCELLED -> 3;
            default -> 0; // unknown — never beats a known non-zero state, never wrongly pins
        };
    }

    /** True for a payment status that is terminal (succeeded/failed/cancelled). */
    public static boolean isPaymentTerminal(String status) {
        return PaymentResponse.STATUS_SUCCEEDED.equals(status)
                || PaymentResponse.STATUS_FAILED.equals(status)
                || PaymentResponse.STATUS_CANCELLED.equals(status);
    }

    /** Monotonic rank for a refund status; higher = further along. */
    public static int refundRank(String status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case RefundResponse.STATUS_PENDING -> 0;
            case RefundResponse.STATUS_SUCCEEDED, RefundResponse.STATUS_FAILED -> 1;
            default -> 0;
        };
    }

    /** True for a refund status that is terminal (succeeded/failed). */
    public static boolean isRefundTerminal(String status) {
        return RefundResponse.STATUS_SUCCEEDED.equals(status)
                || RefundResponse.STATUS_FAILED.equals(status);
    }

    /**
     * Decides whether an incoming payment status may overwrite the existing one in the projection.
     * Accept when the existing status is null/blank (insert), OR the new rank is &ge; the existing rank
     * AND we are not overwriting a terminal with a DIFFERENT status (the first terminal wins).
     */
    public static boolean acceptPaymentStatus(String existing, String incoming) {
        if (existing == null || existing.isBlank()) {
            return true;
        }
        if (isPaymentTerminal(existing) && !existing.equals(incoming)) {
            return false; // first terminal wins — never flip to another terminal or back to non-terminal
        }
        return paymentRank(incoming) >= paymentRank(existing);
    }

    /** Refund analogue of {@link #acceptPaymentStatus}. */
    public static boolean acceptRefundStatus(String existing, String incoming) {
        if (existing == null || existing.isBlank()) {
            return true;
        }
        if (isRefundTerminal(existing) && !existing.equals(incoming)) {
            return false;
        }
        return refundRank(incoming) >= refundRank(existing);
    }
}
