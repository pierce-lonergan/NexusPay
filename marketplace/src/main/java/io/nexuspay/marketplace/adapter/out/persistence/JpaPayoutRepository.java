package io.nexuspay.marketplace.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for payouts.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface JpaPayoutRepository extends JpaRepository<PayoutEntity, String> {

    List<PayoutEntity> findByConnectedAccountId(String connectedAccountId);

    List<PayoutEntity> findByStatusAndScheduledAtBefore(String status, Instant cutoff);

    // SEC-BATCH-1: tenant-scoped finders.
    Optional<PayoutEntity> findByIdAndTenantId(String id, String tenantId);

    List<PayoutEntity> findByConnectedAccountIdAndTenantId(String connectedAccountId, String tenantId);
}
