package io.nexuspay.payment.domain.routing.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a routing attempt fails (all cascade attempts exhausted).
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public record RouteFailed(
        UUID decisionId,
        String tenantId,
        String paymentId,
        String strategyUsed,
        int cascadeAttempts,
        String lastDeclineCode,
        String reason,
        Instant occurredAt
) {}
