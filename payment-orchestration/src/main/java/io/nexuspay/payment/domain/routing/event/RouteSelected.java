package io.nexuspay.payment.domain.routing.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Event published when a routing decision is made.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public record RouteSelected(
        UUID decisionId,
        String tenantId,
        String paymentId,
        String selectedPsp,
        String strategyUsed,
        Map<String, Double> candidateScores,
        long decisionLatencyMs,
        Instant occurredAt
) {}
