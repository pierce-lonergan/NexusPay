package io.nexuspay.payment.application.port.routing;

import io.nexuspay.payment.domain.routing.RoutingDecision;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for persisting routing decisions (audit trail).
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public interface RoutingDecisionRepository {

    RoutingDecision save(RoutingDecision decision);

    Optional<RoutingDecision> findById(UUID id);

    Optional<RoutingDecision> findByPaymentId(String paymentId);

    List<RoutingDecision> findByAbTestId(UUID abTestId);
}
