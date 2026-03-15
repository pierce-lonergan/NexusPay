package io.nexuspay.workflow.application;

import java.io.Serializable;

/**
 * Request DTO for the payment workflow.
 * Serializable for Temporal workflow persistence.
 *
 * @since 0.2.0 (Sprint 2.2)
 */
public record PaymentWorkflowRequest(
        String paymentId,
        long amountInMinorUnits,
        String currency,
        String paymentMethod,
        String tenantId,
        String idempotencyKey
) implements Serializable {
}
