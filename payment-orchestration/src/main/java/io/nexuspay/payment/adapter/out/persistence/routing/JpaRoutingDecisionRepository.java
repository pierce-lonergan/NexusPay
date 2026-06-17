package io.nexuspay.payment.adapter.out.persistence.routing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for routing decision entities.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Repository
public interface JpaRoutingDecisionRepository extends JpaRepository<RoutingDecisionEntity, UUID> {

    Optional<RoutingDecisionEntity> findByPaymentId(String paymentId);

    /** SEC-27: tenant-scoped by-id lookup; the tenant predicate is pushed to SQL. */
    Optional<RoutingDecisionEntity> findByIdAndTenantId(UUID id, String tenantId);

    /** SEC-27: tenant-scoped by-payment lookup; the tenant predicate is pushed to SQL. */
    Optional<RoutingDecisionEntity> findByPaymentIdAndTenantId(String paymentId, String tenantId);

    List<RoutingDecisionEntity> findByAbTestId(UUID abTestId);
}
