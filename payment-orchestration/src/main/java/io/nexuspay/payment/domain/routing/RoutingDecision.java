package io.nexuspay.payment.domain.routing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records a routing decision with full audit trail: which strategy was used,
 * all candidate scores, and the selected PSP ordering for cascade attempts.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public record RoutingDecision(
        UUID id,
        String tenantId,
        String paymentId,
        String strategyUsed,
        UUID configId,
        String selectedPsp,
        Map<String, Double> candidateScores,
        List<String> cascadeOrder,
        UUID abTestId,
        String abTestGroup,
        Instant decidedAt,
        long decisionLatencyMs
) {

    /**
     * Creates a routing decision from strategy evaluation results.
     */
    public static RoutingDecision create(
            String tenantId, String paymentId, String strategy, UUID configId,
            String selectedPsp, Map<String, Double> scores, List<String> cascadeOrder,
            long latencyMs) {
        return new RoutingDecision(
                UUID.randomUUID(), tenantId, paymentId, strategy, configId,
                selectedPsp, scores, cascadeOrder, null, null,
                Instant.now(), latencyMs
        );
    }

    /**
     * Creates a routing decision that is part of an A/B test.
     */
    public RoutingDecision withAbTest(UUID abTestId, String group) {
        return new RoutingDecision(id, tenantId, paymentId, strategyUsed, configId,
                selectedPsp, candidateScores, cascadeOrder, abTestId, group,
                decidedAt, decisionLatencyMs);
    }
}
