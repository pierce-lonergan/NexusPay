package io.nexuspay.payment.application.service.strategy;

import io.nexuspay.payment.domain.routing.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Primary-secondary failover strategy.
 * Always routes to the first eligible PSP in the configured order.
 * Cascade order follows the exact configured PSP list.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Component
public class FailoverStrategy implements RoutingStrategy {

    @Override
    public String name() {
        return "FAILOVER";
    }

    @Override
    public RoutingDecision selectPsp(RoutingContext context, List<PspCandidate> candidates) {
        long start = System.nanoTime();
        List<PspCandidate> eligible = candidates.stream()
                .filter(PspCandidate::isEligible)
                .collect(Collectors.toList());

        if (eligible.isEmpty()) {
            throw new IllegalStateException("No eligible PSP candidates");
        }

        // Always pick the first eligible (highest priority)
        PspCandidate selected = eligible.get(0);

        List<String> cascadeOrder = eligible.stream()
                .map(PspCandidate::pspConnector)
                .collect(Collectors.toList());

        Map<PspCandidate, Double> scores = scoreCandidates(context, candidates);
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        Map<String, Double> scoreMap = scores.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().pspConnector(), Map.Entry::getValue));

        return RoutingDecision.create(context.tenantId(), context.paymentId(), name(),
                null, selected.pspConnector(), scoreMap, cascadeOrder, latencyMs);
    }

    @Override
    public Map<PspCandidate, Double> scoreCandidates(RoutingContext context, List<PspCandidate> candidates) {
        Map<PspCandidate, Double> scores = new HashMap<>();
        int total = candidates.size();
        int rank = 0;

        for (PspCandidate candidate : candidates) {
            if (!candidate.isEligible()) {
                scores.put(candidate, 0.0);
            } else {
                // Higher score for earlier position in the list
                scores.put(candidate, (double) (total - rank) / total);
                rank++;
            }
        }
        return scores;
    }
}
