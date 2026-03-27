package io.nexuspay.marketplace.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for payouts.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface JpaPayoutRepository extends JpaRepository<PayoutEntity, String> {

    List<PayoutEntity> findByConnectedAccountId(String connectedAccountId);

    List<PayoutEntity> findByStatusAndScheduledAtBefore(String status, Instant cutoff);
}
