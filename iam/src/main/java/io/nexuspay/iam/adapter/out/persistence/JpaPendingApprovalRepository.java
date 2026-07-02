package io.nexuspay.iam.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JpaPendingApprovalRepository extends JpaRepository<PendingApprovalEntity, String> {

    List<PendingApprovalEntity> findAllByStatusAndTenantId(String status, String tenantId);

    /**
     * WAVE1 (GAP-068 review fix): the idempotent-re-request lookup. A repeated/retried maker call
     * for the same (action, resource, tenant) must return the EXISTING pending approval instead of
     * minting a duplicate row — duplicates become permanently-stuck poison rows once one approval
     * executes (every review attempt claims, fails the domain state guard, and rolls back to
     * PENDING forever). Oldest-first so concurrent duplicates that slipped the check-then-act
     * still converge on one canonical row.
     */
    Optional<PendingApprovalEntity> findFirstByActionAndResourceIdAndTenantIdAndStatusOrderByCreatedAtAsc(
            String action, String resourceId, String tenantId, String status);

    /**
     * Atomically claims a PENDING approval, moving it to {@code newStatus} only if
     * it is still PENDING and belongs to the tenant. Returns rows affected (1 = this
     * caller won the transition, 0 = already processed / wrong tenant). This makes
     * approve/reject execute-once even under concurrent requests (maker-checker for
     * money — B-009), without a read-then-write TOCTOU.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PendingApprovalEntity a SET a.status = :newStatus, a.reviewedBy = :reviewer, "
            + "a.reviewedAt = :now WHERE a.id = :id AND a.tenantId = :tenantId AND a.status = 'PENDING'")
    int transitionFromPending(@Param("id") String id,
                              @Param("tenantId") String tenantId,
                              @Param("newStatus") String newStatus,
                              @Param("reviewer") String reviewer,
                              @Param("now") Instant now);

    // ----------------------------------------------------------------------------
    // B-022: stuck-APPROVED refund reconciler support.
    // ----------------------------------------------------------------------------

    /**
     * Cross-tenant DISCOVERY finder for the refund reconciler (B-022). Run under the
     * scheduler's {@code @SystemTransactional} SYSTEM (BYPASSRLS) pin so it sees every
     * tenant's stuck refunds (mirrors RenewalScheduler.findDueForRenewal). Selects the
     * "stuck APPROVED-but-unexecuted refund" set:
     * <ul>
     *   <li>{@code status = 'APPROVED'} — already past maker-checker, not pending/rejected;</li>
     *   <li>{@code action = 'refund'} — ONLY refunds have a downstream gateway side-effect to
     *       re-drive; reject/non-refund approvals must never be picked up (load-bearing filter);</li>
     *   <li>{@code executed_at IS NULL} — not provably executed yet;</li>
     *   <li>{@code reconcile_attempts < :maxAttempts} — bounded; a permanently-failing refund stops
     *       being hammered (it is surfaced separately by {@link #findExhaustedRefunds});</li>
     *   <li>backoff gate: {@code next_reconcile_at IS NULL OR <= :now}.</li>
     * </ul>
     * Ordered by created_at (oldest stuck first), paged via {@code Pageable} for a bounded batch.
     */
    @Query("SELECT a FROM PendingApprovalEntity a "
            + "WHERE a.status = 'APPROVED' AND a.action = 'refund' AND a.executedAt IS NULL "
            + "AND a.reconcileAttempts < :maxAttempts "
            + "AND (a.nextReconcileAt IS NULL OR a.nextReconcileAt <= :now) "
            + "ORDER BY a.createdAt ASC")
    List<PendingApprovalEntity> findApprovedUnexecutedRefunds(@Param("now") Instant now,
                                                              @Param("maxAttempts") int maxAttempts,
                                                              Pageable page);

    /**
     * Re-loads a single approval FOR UPDATE inside the per-item tenant-bound tx. Secondary
     * intra-cycle guard: lets {@code reconcileOne} re-check {@code executed_at IS NULL} under a
     * row lock before re-driving, so two cycles cannot both re-drive the same row at the same
     * instant (the PSP idempotency key is the primary no-double-pay guarantee; this just avoids
     * a redundant deduped POST). Runs on the RLS-bound APP role, so RLS USING already scopes it
     * to the bound tenant.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM PendingApprovalEntity a WHERE a.id = :id")
    Optional<PendingApprovalEntity> findByIdForUpdate(@Param("id") String id);

    /**
     * Marks a refund EXECUTED — conditional on {@code executed_at IS NULL} so two writers can
     * never both believe they set it (returns 1 only for the writer that actually flips it). Bound
     * to {@code id} AND {@code tenantId}: under enforced RLS the UPDATE only satisfies WITH CHECK
     * because it is run inside {@code callInTenant(approval.getTenantId(), ...)}, i.e. the bound
     * tenant equals the row's own tenant. status is left APPROVED. Resets the error/backoff fields
     * on success.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PendingApprovalEntity a SET a.executedAt = :now, a.lastReconcileError = NULL, "
            + "a.nextReconcileAt = NULL "
            + "WHERE a.id = :id AND a.tenantId = :tenantId AND a.executedAt IS NULL")
    int markRefundExecuted(@Param("id") String id,
                           @Param("tenantId") String tenantId,
                           @Param("now") Instant now);

    /**
     * Records a reconcile FAILURE — increments {@code reconcile_attempts}, sets the backoff gate
     * and the last error, and LEAVES {@code executed_at NULL} so the row is re-selected next cycle
     * (never flipped to a terminal state that strands it). Bound to {@code id} AND {@code tenantId}
     * for the same RLS WITH CHECK reason as {@link #markRefundExecuted}. Guarded on
     * {@code executed_at IS NULL} so a concurrent success that already marked it wins (we never
     * resurrect a marked row into the retry track).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PendingApprovalEntity a SET a.reconcileAttempts = a.reconcileAttempts + 1, "
            + "a.nextReconcileAt = :nextReconcileAt, a.lastReconcileError = :error "
            + "WHERE a.id = :id AND a.tenantId = :tenantId AND a.executedAt IS NULL")
    int recordReconcileFailure(@Param("id") String id,
                               @Param("tenantId") String tenantId,
                               @Param("nextReconcileAt") Instant nextReconcileAt,
                               @Param("error") String error);

    /**
     * Records a BENIGN re-check for a PSP {@code pending} response (B-022 FIX 2): the PSP ACCEPTED
     * the refund and is settling it asynchronously, so this is NOT a failure. Sets ONLY the backoff
     * gate ({@code next_reconcile_at}) and an informational note — it DELIBERATELY does NOT touch
     * {@code reconcile_attempts}, so a perpetually-{@code pending} refund never advances toward the
     * attempts-exhausted tail and so can never false-page the operator-signal sweep
     * ({@link #findExhaustedRefunds}). Leaves {@code executed_at NULL} so a later cycle re-drives the
     * SAME deduped key and, once the PSP reports {@code succeeded}, marks it executed. Bound to
     * {@code id} AND {@code tenantId} and guarded on {@code executed_at IS NULL} for the identical
     * RLS WITH CHECK / no-resurrect reasons as {@link #recordReconcileFailure}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PendingApprovalEntity a SET a.nextReconcileAt = :nextReconcileAt, "
            + "a.lastReconcileError = :note "
            + "WHERE a.id = :id AND a.tenantId = :tenantId AND a.executedAt IS NULL")
    int recordPendingRecheck(@Param("id") String id,
                             @Param("tenantId") String tenantId,
                             @Param("nextReconcileAt") Instant nextReconcileAt,
                             @Param("note") String note);

    /**
     * OPERATOR-SIGNAL finder (B-022): the attempts-EXHAUSTED tail — APPROVED refunds that ran out
     * of reconcile attempts and are still unexecuted. These are deliberately EXCLUDED from
     * {@link #findApprovedUnexecutedRefunds} (so the PSP is not hammered) but must NEVER be silently
     * stranded — a lower-cadence sweep logs each one at ERROR so an operator can intervene. status
     * is never flipped to a terminal value, so an operator can clear the attempt counter and the
     * next cycle re-drives the SAME idempotency key safely. Run as SYSTEM (cross-tenant).
     */
    @Query("SELECT a FROM PendingApprovalEntity a "
            + "WHERE a.status = 'APPROVED' AND a.action = 'refund' AND a.executedAt IS NULL "
            + "AND a.reconcileAttempts >= :maxAttempts "
            + "ORDER BY a.createdAt ASC")
    List<PendingApprovalEntity> findExhaustedRefunds(@Param("maxAttempts") int maxAttempts);
}
