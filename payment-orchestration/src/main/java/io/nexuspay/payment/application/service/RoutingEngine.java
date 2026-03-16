package io.nexuspay.payment.application.service;

import io.nexuspay.payment.application.fx.CurrencyRoutingService;
import io.nexuspay.payment.application.port.routing.*;
import io.nexuspay.payment.application.port.routing.RoutingConfigRepository.RoutingConfig;
import io.nexuspay.payment.domain.routing.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central routing orchestrator.
 * <ol>
 *   <li>Load tenant routing config (strategy, cascade depth, PSP list)</li>
 *   <li>Filter candidates: currency support, circuit breaker state, fraud module restrictions</li>
 *   <li>Apply configured strategy (or A/B test strategy split)</li>
 *   <li>Return ordered list of PSPs for cascade attempts</li>
 * </ol>
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Service
public class RoutingEngine implements RoutePaymentUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingEngine.class);

    private final Map<String, RoutingStrategy> strategiesByName;
    private final RoutingConfigRepository configRepository;
    private final PspHealthRepository healthRepository;
    private final RoutingDecisionRepository decisionRepository;
    private final CurrencyRoutingService currencyRoutingService;

    public RoutingEngine(
            List<RoutingStrategy> strategies,
            RoutingConfigRepository configRepository,
            PspHealthRepository healthRepository,
            RoutingDecisionRepository decisionRepository,
            CurrencyRoutingService currencyRoutingService) {
        this.strategiesByName = strategies.stream()
                .collect(Collectors.toMap(s -> s.name().toUpperCase(), Function.identity()));
        this.configRepository = configRepository;
        this.healthRepository = healthRepository;
        this.decisionRepository = decisionRepository;
        this.currencyRoutingService = currencyRoutingService;
    }

    @Override
    public RoutingDecision route(RoutingContext context) {
        // 1. Load tenant config
        RoutingConfig config = configRepository.findActiveByTenant(context.tenantId())
                .orElse(RoutingConfig.defaults(context.tenantId()));

        // 2. Build candidate list with health data
        List<PspCandidate> candidates = buildCandidates(context, config);

        if (candidates.isEmpty()) {
            throw new IllegalStateException("No PSP candidates available for payment " + context.paymentId());
        }

        // 3. Filter by fraud clearance
        if (!context.fraudCleared()) {
            LOG.warn("Payment {} not cleared by fraud module, blocking routing", context.paymentId());
            throw new IllegalStateException("Payment blocked by fraud module");
        }

        // 4. Select strategy and route
        RoutingStrategy strategy = resolveStrategy(config.strategy());
        RoutingDecision decision = strategy.selectPsp(context, candidates);

        // 5. Limit cascade order to max depth
        List<String> limitedCascade = decision.cascadeOrder().stream()
                .limit(config.maxCascadeDepth())
                .collect(Collectors.toList());

        decision = new RoutingDecision(
                decision.id(), decision.tenantId(), decision.paymentId(),
                decision.strategyUsed(), config.id(),
                decision.selectedPsp(), decision.candidateScores(),
                limitedCascade, decision.abTestId(), decision.abTestGroup(),
                decision.decidedAt(), decision.decisionLatencyMs()
        );

        // 6. Persist for audit
        decisionRepository.save(decision);

        LOG.info("Routed payment {} to {} via {} strategy (cascade: {}, latency: {}ms)",
                context.paymentId(), decision.selectedPsp(), decision.strategyUsed(),
                limitedCascade.size(), decision.decisionLatencyMs());

        return decision;
    }

    private List<PspCandidate> buildCandidates(RoutingContext context, RoutingConfig config) {
        // Get PSPs that support the payment currency
        List<String> currencyCapable = currencyRoutingService.findPresentmentCapablePsps(
                context.currency(), context.amount());

        // If config has a specific PSP list, use it; otherwise use all currency-capable
        List<String> pspPool = config.pspList().isEmpty() ? currencyCapable : config.pspList();

        // Filter to only those that support the currency
        Set<String> currencySet = new HashSet<>(currencyCapable);

        List<PspCandidate> candidates = new ArrayList<>();
        for (String psp : pspPool) {
            PspHealthSnapshot health = healthRepository.getHealth(psp).orElse(null);
            boolean supportsCurrency = currencySet.contains(psp);

            candidates.add(new PspCandidate(
                    psp,
                    health != null && health.circuitBreakerOpen(),
                    health != null ? health.authRate() : 0.95,
                    health != null ? health.latencyP95Ms() : 200.0,
                    BigDecimal.ZERO,
                    1,
                    supportsCurrency,
                    false
            ));
        }

        return candidates;
    }

    private RoutingStrategy resolveStrategy(String strategyName) {
        RoutingStrategy strategy = strategiesByName.get(strategyName.toUpperCase());
        if (strategy == null) {
            LOG.warn("Unknown strategy '{}', falling back to SUCCESS_RATE", strategyName);
            strategy = strategiesByName.get("SUCCESS_RATE");
        }
        if (strategy == null) {
            throw new IllegalStateException("No routing strategies available");
        }
        return strategy;
    }
}
