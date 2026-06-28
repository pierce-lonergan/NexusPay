package io.nexuspay.payment.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Domain response representing a payment's current state.
 * Unified across all gateway providers.
 */
public record PaymentResponse(
        String gatewayPaymentId,
        String status,
        long amount,
        String currency,
        String captureMethod,
        String customerId,
        String connectorName,
        String connectorTransactionId,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Map<String, Object> metadata,
        NextAction nextAction
) {
    /**
     * Payment lifecycle statuses (mirrors HyperSwitch states).
     */
    public static final String STATUS_REQUIRES_PAYMENT_METHOD = "requires_payment_method";
    public static final String STATUS_REQUIRES_CONFIRMATION = "requires_confirmation";
    public static final String STATUS_REQUIRES_CAPTURE = "requires_capture";
    /**
     * TEST-6 (A3): a 3DS/SCA next-action is required — the intent is NON-terminal and carries a
     * {@link #nextAction} stub (e.g. a redirect URL) so an integrator can exercise their SCA/redirect
     * handling. Value matches the literal {@code "requires_action"} that {@code ResponseMapper.toConfirmResponse}
     * already recognizes, so the SDK confirm path stays consistent. No terminal webhook is synthesized for a
     * requires_action response (the gateway synthesizes only on success/failed — a non-terminal state emits
     * nothing).
     */
    public static final String STATUS_REQUIRES_ACTION = "requires_action";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    /**
     * TEST-6 (A3): a typed 3DS/redirect next-action carried on a {@link #STATUS_REQUIRES_ACTION} response.
     * Two plain String fields only ({@code type} e.g. {@code "redirect_to_url"}, {@code url} the redirect
     * target) — NO {@code Instant}, NO {@code ObjectMapper} (L-071). Nullable + dropped on the wire for any
     * non-action response.
     *
     * <p><b>DELIBERATE BLUEPRINT DEVIATION (conscious contract decision, not drift).</b> The TEST-6
     * blueprint sketched the 3DS stub as the Stripe-style nested-by-type envelope
     * {@code {type:"redirect_to_url", redirect_to_url:{url}}}. This implementation uses the FLAT
     * {@code {type, url}} shape instead, and does so consistently across the whole stack — this domain
     * record, {@code PaymentApiResponse.NextAction}, the SDK {@code NextAction}, the LOCAL_DEV catalog, and
     * every test all agree on {@code {type, url}}. It is NOT a wire mismatch. The reason: INT-6's
     * pre-existing {@code ConfirmResponse.NextAction} already locked {@code {type, url}}, so keeping the same
     * flat shape means the API exposes EXACTLY ONE {@code next_action} shape on both the confirm path and the
     * payment path (one contract for an integrator to learn, not two). The trade-off: a flat {@code {type,
     * url}} cannot represent a non-redirect next-action (e.g. {@code display_bank_transfer_instructions},
     * {@code verify_with_microdeposits}) without a breaking reshape — should such a non-redirect action ever
     * be needed, that is the breaking change to plan, and the nested-by-type envelope would be reconsidered
     * then. Until then, alignment with INT-6 is the lower-risk choice (the alternative would FORK the
     * already-shipped INT-6 contract). See ADR / the TEST-6 summary for the full rationale.</p>
     */
    public record NextAction(String type, String url) {
    }

    /**
     * TEST-6 BACK-COMPAT (mirrors the TEST-3c {@code PaymentRequest} 10-arg compat ctor): the 12-arg
     * convenience constructor delegates to the canonical 13-arg record constructor with {@code nextAction =
     * null}, so the 16 existing {@code new PaymentResponse(...)} call sites (incl. {@code
     * HyperSwitchResponseMapper} and the mock's own {@code withStatus}/{@code createPayment}) stay green
     * without touching every site. Only the requires_action path sets {@code nextAction}.
     */
    public PaymentResponse(
            String gatewayPaymentId,
            String status,
            long amount,
            String currency,
            String captureMethod,
            String customerId,
            String connectorName,
            String connectorTransactionId,
            String errorCode,
            String errorMessage,
            Instant createdAt,
            Map<String, Object> metadata) {
        this(gatewayPaymentId, status, amount, currency, captureMethod, customerId, connectorName,
                connectorTransactionId, errorCode, errorMessage, createdAt, metadata, null);
    }

    public boolean isSuccessful() {
        return STATUS_SUCCEEDED.equals(status);
    }

    public boolean requiresCapture() {
        return STATUS_REQUIRES_CAPTURE.equals(status);
    }

    /** TEST-6 (A3): true only for a {@link #STATUS_REQUIRES_ACTION} (3DS/SCA) response. */
    public boolean requiresAction() {
        return STATUS_REQUIRES_ACTION.equals(status);
    }

    public boolean isFailed() {
        return STATUS_FAILED.equals(status);
    }

    /**
     * TEST-6 (A3): returns a copy of this response carrying the given {@code nextAction}, preserving every
     * other field. Used by the mock to attach the 3DS redirect stub to a requires_action response.
     */
    public PaymentResponse withNextAction(NextAction nextAction) {
        return new PaymentResponse(
                gatewayPaymentId, status, amount, currency, captureMethod, customerId, connectorName,
                connectorTransactionId, errorCode, errorMessage, createdAt, metadata, nextAction);
    }

    /**
     * GAP-078 (critique v3 F5): returns a copy of this response with {@code createdAt} replaced, preserving
     * EVERY other field — including {@link #nextAction} (uses the full 13-arg constructor, NOT the 12-arg
     * compat ctor, so a {@code requires_action} 3DS stub is not lost). Used by {@code GatedPaymentGateway}'s
     * mock branches to re-stamp the mock's createdAt with the per-tenant TEST CLOCK's frozen instant, so the
     * GAP-076 projection (which inherits created_at from the response) and its list ordering become
     * deterministic. Touches ONLY the createdAt — never any live-rail timestamp.
     */
    public PaymentResponse withCreatedAt(Instant createdAt) {
        return new PaymentResponse(
                gatewayPaymentId, status, amount, currency, captureMethod, customerId, connectorName,
                connectorTransactionId, errorCode, errorMessage, createdAt, metadata, nextAction);
    }
}
