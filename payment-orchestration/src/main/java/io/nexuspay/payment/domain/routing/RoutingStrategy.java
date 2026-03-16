package io.nexuspay.payment.domain.routing;

import java.util.List;
import java.util.Map;

/**
 * Pluggable strategy interface for PSP routing decisions.
 * Implementations score candidate PSPs based on different optimization criteria.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public interface RoutingStrategy {

    /**
     * Returns the strategy identifier (e.g., "COST_BASED", "SUCCESS_RATE").
     */
    String name();

    /**
     * Selects the best PSP for the given routing context from a list of candidates.
     * Returns a full decision with scores and rationale.
     */
    RoutingDecision selectPsp(RoutingContext context, List<PspCandidate> candidates);

    /**
     * Returns a score for each candidate PSP. Higher score = preferred.
     * Scoring allows strategies to be composed (weighted combination).
     *
     * @param context    payment and tenant context
     * @param candidates filtered PSP candidates
     * @return map of candidate → score (0.0 to 1.0)
     */
    Map<PspCandidate, Double> scoreCandidates(RoutingContext context, List<PspCandidate> candidates);
}
