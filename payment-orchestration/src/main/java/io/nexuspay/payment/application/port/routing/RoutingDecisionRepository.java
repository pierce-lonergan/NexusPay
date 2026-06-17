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

    /**
     * SEC-27: tenant-scoped by-id lookup. Empty result means "absent OR owned by another tenant" —
     * pair with {@code TenantOwnership.require} so a foreign id 404s without an existence oracle.
     */
    Optional<RoutingDecision> findByIdAndTenantId(UUID id, String tenantId);

    Optional<RoutingDecision> findByPaymentId(String paymentId);

    /**
     * SEC-27: tenant-scoped lookup of the decision for a payment. Empty result means "absent OR owned
     * by another tenant" — pair with {@code TenantOwnership.require} so a foreign payment id 404s.
     */
    Optional<RoutingDecision> findByPaymentIdAndTenantId(String paymentId, String tenantId);

    List<RoutingDecision> findByAbTestId(UUID abTestId);
}
