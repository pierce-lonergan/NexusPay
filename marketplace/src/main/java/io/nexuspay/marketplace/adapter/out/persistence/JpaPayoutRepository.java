package io.nexuspay.marketplace.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     *
     * <p>SEC-25: the same UPDATE now also stamps {@code processing_since = now()} — the single source
     * of "when did this row enter PROCESSING". The PayoutReconciler finder uses it to detect a row
     * stranded PROCESSING after a crash between this claim COMMIT and the terminal markPaid/markFailed
     * save (created_at is creation time, NOT claim time, so it cannot serve here).</p>
     */
    @Modifying
    @Query(value = "UPDATE payouts SET status = 'PROCESSING', processing_since = now() "
            + "WHERE id = :id AND status = 'PENDING'",
            nativeQuery = true)
    int claimForProcessing(@Param("id") String id);

    // --- SEC-25: stuck-PROCESSING reconciler queries (cross-tenant; run under @SystemTransactional) ---

    /**
     * SEC-25 finder: payouts stuck in PROCESSING whose claim is OLDER than {@code cutoff}, not attempt-
     * exhausted, and past their backoff gate — the re-drivable set. {@code processing_since IS NULL}
     * (pre-SEC-25 / legacy rows that were stranded before this column existed) is treated as
     * INFINITELY old via {@code COALESCE(..., 'epoch')} so those rows are still recovered. Oldest first,
     * bounded by {@code batchSize}. The partial idx_payouts_reconcile (V4032) covers it.
     */
    @Query(value = """
            SELECT * FROM payouts
             WHERE status = 'PROCESSING'
               AND COALESCE(processing_since, 'epoch'::timestamptz) < :cutoff
               AND reconcile_attempts < :maxAttempts
               AND (next_reconcile_at IS NULL OR next_reconcile_at <= :now)
             ORDER BY COALESCE(processing_since, 'epoch'::timestamptz) ASC
             LIMIT :batchSize
            """, nativeQuery = true)
    List<PayoutEntity> findStuckProcessing(@Param("cutoff") Instant cutoff,
                                           @Param("now") Instant now,
                                           @Param("maxAttempts") int maxAttempts,
                                           @Param("batchSize") int batchSize);

    /**
     * SEC-25 operator-signal finder: the attempts-EXHAUSTED tail — PROCESSING payouts out of reconcile
     * attempts, still un-terminal. Deliberately EXCLUDED from {@link #findStuckProcessing} (so the PSP
     * is not hammered) but surfaced at ERROR so a human can intervene; status is never flipped, so the
     * row stays hand-recoverable (clearing reconcile_attempts re-drives the SAME idempotency key safely).
     */
    @Query(value = "SELECT * FROM payouts WHERE status = 'PROCESSING' AND reconcile_attempts >= :maxAttempts",
            nativeQuery = true)
    List<PayoutEntity> findExhaustedProcessing(@Param("maxAttempts") int maxAttempts);

    /**
     * SEC-25 intra-cycle guard: re-load FOR UPDATE, returning empty unless still PROCESSING. If the
     * original cycle (or a concurrent reconcile pass) already finalized the row to PAID/FAILED, this
     * returns empty and the re-drive is skipped. Mirrors B-022's {@code findByIdForUpdate}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PayoutEntity p WHERE p.id = :id AND p.status = 'PROCESSING'")
    Optional<PayoutEntity> findProcessingByIdForUpdate(@Param("id") String id);

    /**
     * SEC-25 terminal transition: PROCESSING -> PAID. Conditional on {@code status = 'PROCESSING'} AND
     * {@code tenant_id} (so two writers cannot both finalize, and RLS WITH CHECK on payouts (V4020)
     * admits it only when the bound tenant == the row's tenant). Clears the last reconcile error.
     */
    @Modifying
    @Query(value = "UPDATE payouts SET status = 'PAID', paid_at = now(), external_reference = :ref, "
            + "last_reconcile_error = NULL "
            + "WHERE id = :id AND tenant_id = :tenantId AND status = 'PROCESSING'",
            nativeQuery = true)
    int markPaidById(@Param("id") String id,
                     @Param("tenantId") String tenantId,
                     @Param("ref") String externalReference);

    /**
     * SEC-25 terminal transition: PROCESSING -> FAILED (terminal PSP failure). Conditional on
     * PROCESSING + tenant for the same idempotency / RLS WITH CHECK reasons as {@link #markPaidById}.
     */
    @Modifying
    @Query(value = "UPDATE payouts SET status = 'FAILED', failure_reason = :reason "
            + "WHERE id = :id AND tenant_id = :tenantId AND status = 'PROCESSING'",
            nativeQuery = true)
    int markFailedById(@Param("id") String id,
                       @Param("tenantId") String tenantId,
                       @Param("reason") String reason);

    /**
     * SEC-25 transient-failure bookkeeping: bump {@code reconcile_attempts}, set the backoff gate and
     * last error, and LEAVE the row PROCESSING so it re-drives next cycle (never a stranding terminal
     * state). Conditional on PROCESSING + tenant. Mirrors B-022's recordReconcileFailure.
     */
    @Modifying
    @Query(value = "UPDATE payouts SET reconcile_attempts = reconcile_attempts + 1, "
            + "next_reconcile_at = :nextReconcileAt, last_reconcile_error = :error "
            + "WHERE id = :id AND tenant_id = :tenantId AND status = 'PROCESSING'",
            nativeQuery = true)
    int recordReconcileFailureById(@Param("id") String id,
                                   @Param("tenantId") String tenantId,
                                   @Param("nextReconcileAt") Instant nextReconcileAt,
                                   @Param("error") String error);
}
