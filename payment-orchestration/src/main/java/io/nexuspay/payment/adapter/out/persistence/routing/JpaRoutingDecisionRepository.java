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

    List<RoutingDecisionEntity> findByAbTestId(UUID abTestId);
}
