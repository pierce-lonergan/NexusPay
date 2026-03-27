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
    List<ConnectedAccount> findAccountsByTenantId(String tenantId);
    void deleteAccount(String id);

    // --- SplitPayment ---
    SplitPayment saveSplitPayment(SplitPayment splitPayment);
    Optional<SplitPayment> findSplitPaymentById(String id);
    List<SplitPayment> findSplitPaymentsByPaymentId(String paymentId);

    // --- SplitRule ---
    SplitRule saveSplitRule(SplitRule rule);
    List<SplitRule> findRulesBySplitPaymentId(String splitPaymentId);

    // --- Payout ---
    Payout savePayout(Payout payout);
    Optional<Payout> findPayoutById(String id);
    List<Payout> findPayoutsByAccountId(String connectedAccountId);
    List<Payout> findPendingPayoutsDueBefore(Instant cutoff);

    // --- PlatformFee ---
    PlatformFee savePlatformFee(PlatformFee fee);
    Optional<PlatformFee> findFeesBySplitPaymentId(String splitPaymentId);
}
