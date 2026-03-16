package io.nexuspay.payment.application.service.strategy;

import io.nexuspay.payment.domain.routing.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Simple round-robin across eligible PSPs.
 * Each request goes to the next PSP in sequence.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Component
public class RoundRobinStrategy implements RoutingStrategy {

    private final AtomicLong counter = new AtomicLong(0);

    @Override
    public String name() {
        return "ROUND_ROBIN";
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

        int index = (int) (counter.getAndIncrement() % eligible.size());
        PspCandidate selected = eligible.get(index);

        // Rotate cascade order starting from selected
        List<String> cascadeOrder = new ArrayList<>();
        for (int i = 0; i < eligible.size(); i++) {
            cascadeOrder.add(eligible.get((index + i) % eligible.size()).pspConnector());
        }

        Map<PspCandidate, Double> scores = scoreCandidates(context, candidates);
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        Map<String, Double> scoreMap = scores.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().pspConnector(), Map.Entry::getValue));

        return RoutingDecision.create(context.tenantId(), context.paymentId(), name(),
                null, selected.pspConnector(), scoreMap, cascadeOrder, latencyMs);
    }

    @Override
    public Map<PspCandidate, Double> scoreCandidates(RoutingContext context, List<PspCandidate> candidates) {
        // All eligible candidates get equal score
        Map<PspCandidate, Double> scores = new HashMap<>();
        for (PspCandidate candidate : candidates) {
            scores.put(candidate, candidate.isEligible() ? 1.0 : 0.0);
        }
        return scores;
    }
}
