package io.nexuspay.payment.application.service.strategy;

import io.nexuspay.payment.domain.routing.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Routes to the PSP with the lowest latency (p95).
 * Excludes PSPs with p95 above the configured threshold.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Component
public class LatencyBasedStrategy implements RoutingStrategy {

    @Override
    public String name() {
        return "LATENCY";
    }

    @Override
    public RoutingDecision selectPsp(RoutingContext context, List<PspCandidate> candidates) {
        long start = System.nanoTime();
        Map<PspCandidate, Double> scores = scoreCandidates(context, candidates);

        PspCandidate best = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow(() -> new IllegalStateException("No eligible PSP candidates"));

        List<String> cascadeOrder = scores.entrySet().stream()
                .sorted(Map.Entry.<PspCandidate, Double>comparingByValue().reversed())
                .map(e -> e.getKey().pspConnector())
                .collect(Collectors.toList());

        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        Map<String, Double> scoreMap = scores.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().pspConnector(), Map.Entry::getValue));

        return RoutingDecision.create(context.tenantId(), context.paymentId(), name(),
                null, best.pspConnector(), scoreMap, cascadeOrder, latencyMs);
    }

    @Override
    public Map<PspCandidate, Double> scoreCandidates(RoutingContext context, List<PspCandidate> candidates) {
        Map<PspCandidate, Double> scores = new HashMap<>();
        double maxLatency = candidates.stream()
                .filter(PspCandidate::isEligible)
                .mapToDouble(PspCandidate::latencyP95Ms)
                .max().orElse(1.0);

        for (PspCandidate candidate : candidates) {
            if (!candidate.isEligible()) continue;
            // Inverted: lower latency = higher score
            double score = maxLatency > 0
                    ? 1.0 - (candidate.latencyP95Ms() / maxLatency)
                    : 1.0;
            scores.put(candidate, Math.max(0.01, score));
        }

        return scores;
    }
}
