package io.nexuspay.payment.application.service;

import io.nexuspay.payment.application.port.routing.RoutingConfigRepository;
import io.nexuspay.payment.application.port.routing.RoutingConfigRepository.RoutingConfig;
import io.nexuspay.payment.application.port.routing.RoutingDecisionRepository;
import io.nexuspay.payment.domain.routing.RoutingDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A/B testing service for routing strategies.
 * Splits traffic between two routing configurations and tracks results
 * for statistical significance testing.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Service
public class RoutingAbTestService {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingAbTestService.class);

    private final RoutingConfigRepository configRepository;
    private final RoutingDecisionRepository decisionRepository;
    private final int minSampleSize;
    private final double confidenceLevel;
    private final boolean autoPromote;

    public RoutingAbTestService(
            RoutingConfigRepository configRepository,
            RoutingDecisionRepository decisionRepository,
            @Value("${nexuspay.routing.ab-testing.min-sample-size:1000}") int minSampleSize,
            @Value("${nexuspay.routing.ab-testing.confidence-level:0.95}") double confidenceLevel,
            @Value("${nexuspay.routing.ab-testing.auto-promote:true}") boolean autoPromote) {
        this.configRepository = configRepository;
        this.decisionRepository = decisionRepository;
        this.minSampleSize = minSampleSize;
        this.confidenceLevel = confidenceLevel;
        this.autoPromote = autoPromote;
    }

    /**
     * Selects which config to use for a request based on A/B test traffic split.
     *
     * @param configA primary config (control group)
     * @param configB test config
     * @return the selected config and its group label ("A" or "B")
     */
    public AbTestSelection selectConfig(RoutingConfig configA, RoutingConfig configB) {
        double splitRatio = configB.abTestTraffic();
        if (splitRatio <= 0 || splitRatio >= 1) {
            return new AbTestSelection(configA, "A");
        }

        double random = ThreadLocalRandom.current().nextDouble();
        if (random < splitRatio) {
            return new AbTestSelection(configB, "B");
        }
        return new AbTestSelection(configA, "A");
    }

    /**
     * Gets the A/B test results summary.
     */
    public AbTestSummary getTestSummary(UUID abTestId) {
        List<RoutingDecision> decisions = decisionRepository.findByAbTestId(abTestId);

        long countA = decisions.stream().filter(d -> "A".equals(d.abTestGroup())).count();
        long countB = decisions.stream().filter(d -> "B".equals(d.abTestGroup())).count();

        return new AbTestSummary(
                abTestId,
                countA, countB,
                countA >= minSampleSize && countB >= minSampleSize,
                (double) countA / Math.max(1, countA + countB)
        );
    }

    public record AbTestSelection(RoutingConfig config, String group) {}

    public record AbTestSummary(
            UUID abTestId,
            long groupACount,
            long groupBCount,
            boolean hasSufficientData,
            double groupATrafficShare
    ) {}
}
