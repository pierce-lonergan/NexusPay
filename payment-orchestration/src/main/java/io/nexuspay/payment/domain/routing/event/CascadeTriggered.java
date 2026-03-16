package io.nexuspay.payment.domain.routing.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a cascade failover occurs (primary PSP declined, trying next).
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public record CascadeTriggered(
        UUID decisionId,
        String tenantId,
        String paymentId,
        String failedPsp,
        String nextPsp,
        String declineCode,
        int attemptNumber,
        int maxAttempts,
        Instant occurredAt
) {}
