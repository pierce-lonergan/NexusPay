package io.nexuspay.workflow.application;

import java.io.Serializable;

/**
 * Request DTO for the payment workflow.
 * Serializable for Temporal workflow persistence.
 *
 * <p>DX-5a-ii: {@code live} is the SERVER-DERIVED test/live mode for this charge. It is the durable
 * money-routing flag carried across the Temporal worker thread (where the request-scoped
 * {@code PaymentMode} ThreadLocal is UNSET), so {@code PaymentActivitiesImpl} can thread it into
 * {@code CallContext.serverOther(tenantId, live)} and a TEST-mode payment routed through Temporal reaches
 * the in-process mock, never the real PSP (CHARTER money-safety; the L-064 off-request-path class).
 * {@code TRUE}=live, {@code FALSE}=test, {@code null}=mode not declared (the {@code GatedPaymentGateway}
 * heuristic decides — the current latent behaviour). Any FUTURE code that triggers this workflow MUST
 * stamp {@code live} from the creating caller's server-derived {@code PaymentMode} (never client input),
 * exactly as the DX-5a billing paths stamp {@code subscriptions.is_live}.</p>
 *
 * @since 0.2.0 (Sprint 2.2)
 */
public record PaymentWorkflowRequest(
        String paymentId,
        long amountInMinorUnits,
        String currency,
        String paymentMethod,
        String tenantId,
        String idempotencyKey,
        Boolean live
) implements Serializable {
}
