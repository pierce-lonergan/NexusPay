package io.nexuspay.marketplace.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * SEC-11: atomic per-payout claim — the REAL exactly-once-disbursement guarantee (the lock is
     * only contention reduction). Conditional UPDATE on the existing status column; rows-affected==1
     * means THIS replica/cycle transitioned the row PENDING -> PROCESSING and is the sole disburser.
     * Every other replica's identical UPDATE affects 0 rows and skips. Keyed on the PK id alone, so
     * it works cross-tenant under the scheduler's @SystemTransactional/BYPASSRLS despite payouts RLS.
     * The existing idx_payouts_scheduled ON payouts(status, scheduled_at) (V4002:75) covers the WHERE.
     * Precedent: L-018 / B-009 refund fix ("atomic conditional UPDATE WHERE status=PENDING, rows==1").
     */
    @Modifying
    @Query(value = "UPDATE payouts SET status = 'PROCESSING' WHERE id = :id AND status = 'PENDING'",
            nativeQuery = true)
    int claimForProcessing(@Param("id") String id);
}
