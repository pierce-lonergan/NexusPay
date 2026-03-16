package io.nexuspay.payment.application.service.strategy;

import io.nexuspay.payment.application.port.routing.PspFeeRepository;
import io.nexuspay.payment.domain.routing.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Routes to the cheapest PSP based on fee models.
 * Calculates effective cost per PSP for the transaction amount and
 * selects the lowest-cost option meeting SLA requirements.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Component
public class CostBasedStrategy implements RoutingStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(CostBasedStrategy.class);

    private final PspFeeRepository feeRepository;

    public CostBasedStrategy(PspFeeRepository feeRepository) {
        this.feeRepository = feeRepository;
    }

    @Override
    public String name() {
        return "COST_BASED";
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
        LocalDate today = LocalDate.now();

        // Calculate fees for each candidate
        Map<PspCandidate, BigDecimal> fees = new HashMap<>();
        BigDecimal maxFee = BigDecimal.ZERO;

        for (PspCandidate candidate : candidates) {
            if (!candidate.isEligible()) continue;

            BigDecimal fee = feeRepository.findEffective(
                    context.tenantId(), candidate.pspConnector(), context.currency(), today
            ).map(model -> model.calculateFee(context.amount()))
             .orElse(candidate.effectiveFee());

            fees.put(candidate, fee);
            if (fee.compareTo(maxFee) > 0) maxFee = fee;
        }

        // Score: lower fee = higher score (inverted, normalized to 0-1)
        for (Map.Entry<PspCandidate, BigDecimal> entry : fees.entrySet()) {
            double score = maxFee.compareTo(BigDecimal.ZERO) == 0
                    ? 1.0
                    : 1.0 - entry.getValue().doubleValue() / maxFee.doubleValue();
            scores.put(entry.getKey(), Math.max(0.01, score)); // minimum 0.01 for eligible candidates
        }

        return scores;
    }
}
