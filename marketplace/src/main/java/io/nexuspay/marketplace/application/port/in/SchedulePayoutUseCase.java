package io.nexuspay.marketplace.application.port.in;

import io.nexuspay.marketplace.domain.PayoutMethod;
import io.nexuspay.marketplace.domain.PayoutStatus;

import java.time.Instant;
import java.util.List;

/**
 * Use case for creating, scheduling, and listing payouts to connected accounts.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface SchedulePayoutUseCase {

    PayoutResult createPayout(CreatePayoutCommand command);

    List<PayoutResult> listPayouts(String tenantId, String connectedAccountId);

    PayoutResult getPayout(String payoutId, String tenantId);

    record CreatePayoutCommand(
            String tenantId,
            String connectedAccountId,
            long amount,
            String currency,
            PayoutMethod method,
            Instant scheduledAt
    ) {}

    record PayoutResult(
            String payoutId,
            String connectedAccountId,
            long amount,
            String currency,
            PayoutStatus status,
            PayoutMethod method,
            Instant scheduledAt,
            Instant paidAt,
            String failureReason,
            String externalReference,
            Instant createdAt
    ) {}
}
