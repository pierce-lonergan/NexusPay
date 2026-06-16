package io.nexuspay.marketplace.application.port.out;

import io.nexuspay.marketplace.domain.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for marketplace persistence operations.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface MarketplaceRepository {

    // --- ConnectedAccount ---
    ConnectedAccount saveAccount(ConnectedAccount account);
    Optional<ConnectedAccount> findAccountById(String id);
    /** SEC-BATCH-1: tenant-scoped by-id lookup — empty when absent OR owned by another tenant. */
    Optional<ConnectedAccount> findAccountById(String id, String tenantId);
    List<ConnectedAccount> findAccountsByTenantId(String tenantId);
    void deleteAccount(String id);

    // --- SplitPayment ---
    SplitPayment saveSplitPayment(SplitPayment splitPayment);
    Optional<SplitPayment> findSplitPaymentById(String id);
    /** SEC-BATCH-1: tenant-scoped by-id lookup. */
    Optional<SplitPayment> findSplitPaymentById(String id, String tenantId);
    List<SplitPayment> findSplitPaymentsByPaymentId(String paymentId);

    // --- SplitRule ---
    SplitRule saveSplitRule(SplitRule rule);
    List<SplitRule> findRulesBySplitPaymentId(String splitPaymentId);

    // --- Payout ---
    Payout savePayout(Payout payout);
    Optional<Payout> findPayoutById(String id);
    /** SEC-BATCH-1: tenant-scoped by-id lookup. */
    Optional<Payout> findPayoutById(String id, String tenantId);
    List<Payout> findPayoutsByAccountId(String connectedAccountId);
    /** SEC-BATCH-1: tenant-scoped list — drops the previously-ignored-tenant leak on getPayout list. */
    List<Payout> findPayoutsByAccountId(String connectedAccountId, String tenantId);
    List<Payout> findPendingPayoutsDueBefore(Instant cutoff);
    /**
     * SEC-11: atomically claim a payout for disbursement (PENDING -> PROCESSING). Returns true only
     * for the single winner whose conditional UPDATE affected exactly one row; every concurrent
     * replica/cycle gets false and must NOT disburse. This is the exactly-once-disbursement guarantee.
     * SEC-25: the same UPDATE now also stamps {@code processing_since = now()}.
     */
    boolean claimPayoutForProcessing(String id);

    // --- SEC-25: stuck-PROCESSING recovery (cross-tenant discovery + tenant-bound terminal writes) ---

    /**
     * SEC-25: payouts stuck PROCESSING since before {@code cutoff}, not attempt-exhausted, past their
     * backoff gate ({@code next_reconcile_at <= now}), oldest first, bounded by {@code batchSize}.
     * Cross-tenant — call under {@code @SystemTransactional}.
     */
    List<Payout> findStuckProcessingPayouts(Instant cutoff, Instant now, int maxAttempts, int batchSize);

    /** SEC-25: the attempts-exhausted PROCESSING tail surfaced to operators. Cross-tenant. */
    List<Payout> findExhaustedProcessingPayouts(int maxAttempts);

    /**
     * SEC-25 intra-cycle guard: re-load FOR UPDATE, present only if STILL PROCESSING (empty if the
     * original cycle or a concurrent pass already finalized it). Call inside the row's tenant.
     */
    Optional<Payout> reloadStuckPayoutForUpdate(String id);

    /**
     * SEC-25 terminal transition PROCESSING -> PAID, conditional on PROCESSING + tenant. Returns true
     * iff THIS call flipped the row. Call bound to the row's tenant (RLS WITH CHECK).
     */
    boolean markPayoutPaid(String id, String tenantId, String externalReference);

    /** SEC-25 terminal transition PROCESSING -> FAILED, conditional on PROCESSING + tenant. */
    boolean markPayoutFailed(String id, String tenantId, String reason);

    /**
     * SEC-25 transient-failure bookkeeping: bump attempts, set backoff gate + last error, leave the row
     * PROCESSING (re-drivable). Conditional on PROCESSING + tenant. Call bound to the row's tenant.
     */
    void recordPayoutReconcileFailure(String id, String tenantId, Instant nextReconcileAt, String error);

    // --- PlatformFee ---
    PlatformFee savePlatformFee(PlatformFee fee);
    Optional<PlatformFee> findFeesBySplitPaymentId(String splitPaymentId);
}
