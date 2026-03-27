package io.nexuspay.marketplace.application.port.out;

import io.nexuspay.marketplace.domain.PayoutMethod;

/**
 * Outbound port for executing payout disbursements to connected accounts.
 * Abstracts over bank transfer and card push execution providers.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface PayoutExecutionPort {

    /**
     * Executes a payout disbursement to the connected account's bank or card.
     */
    PayoutExecutionResult execute(PayoutExecutionRequest request);

    record PayoutExecutionRequest(
            String payoutId,
            String connectedAccountId,
            long amount,
            String currency,
            PayoutMethod method
    ) {}

    record PayoutExecutionResult(
            boolean success,
            String externalReference,
            String failureReason
    ) {}
}
