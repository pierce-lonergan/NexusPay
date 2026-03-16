package io.nexuspay.payment.application.service.strategy;

import io.nexuspay.payment.domain.routing.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Routes based on configured weights per PSP.
 * A PSP with weight 3 gets 3x the traffic of a PSP with weight 1.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Component
public class WeightedStrategy implements RoutingStrategy {

    @Override
    public String name() {
        return "WEIGHTED";
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

        // Weighted random selection
        int totalWeight = eligible.stream().mapToInt(PspCandidate::weight).sum();
        if (totalWeight <= 0) totalWeight = eligible.size(); // fallback to equal weight

        int random = ThreadLocalRandom.current().nextInt(totalWeight);
        PspCandidate selected = eligible.get(0);
        int cumulative = 0;
        for (PspCandidate candidate : eligible) {
            cumulative += Math.max(1, candidate.weight());
            if (random < cumulative) {
                selected = candidate;
                break;
            }
        }

        // Cascade ordered by weight descending
        List<String> cascadeOrder = eligible.stream()
                .sorted(Comparator.comparingInt(PspCandidate::weight).reversed())
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
        int totalWeight = candidates.stream()
                .filter(PspCandidate::isEligible)
                .mapToInt(c -> Math.max(1, c.weight()))
                .sum();

        for (PspCandidate candidate : candidates) {
            if (!candidate.isEligible()) {
                scores.put(candidate, 0.0);
            } else {
                scores.put(candidate, (double) Math.max(1, candidate.weight()) / totalWeight);
            }
        }
        return scores;
    }
}
