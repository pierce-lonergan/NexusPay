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
     */
    boolean claimPayoutForProcessing(String id);

    // --- PlatformFee ---
    PlatformFee savePlatformFee(PlatformFee fee);
    Optional<PlatformFee> findFeesBySplitPaymentId(String splitPaymentId);
}
