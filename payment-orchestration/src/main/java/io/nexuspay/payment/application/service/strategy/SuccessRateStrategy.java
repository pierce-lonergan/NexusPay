package io.nexuspay.payment.application.service.strategy;

import io.nexuspay.payment.application.port.routing.AuthRateRepository;
import io.nexuspay.payment.domain.routing.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Routes to the PSP with the highest historical auth rate, filtered by
 * card type, card brand, and issuing region.
 * Uses a 7-day sliding window with hourly granularity stored in Valkey.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Component
public class SuccessRateStrategy implements RoutingStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(SuccessRateStrategy.class);

    private final AuthRateRepository authRateRepository;

    public SuccessRateStrategy(AuthRateRepository authRateRepository) {
        this.authRateRepository = authRateRepository;
    }

    @Override
    public String name() {
        return "SUCCESS_RATE";
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

        for (PspCandidate candidate : candidates) {
            if (!candidate.isEligible()) continue;

            // Try segmented auth rate first (by card attributes), fall back to overall
            double authRate = authRateRepository.getAuthRate(
                    candidate.pspConnector(), context.cardBrand(), context.cardType(), context.issuingCountry()
            ).orElseGet(() -> authRateRepository.getOverallAuthRate(candidate.pspConnector())
                    .orElse(candidate.authRate()));

            scores.put(candidate, authRate);
        }

        return scores;
    }
}
