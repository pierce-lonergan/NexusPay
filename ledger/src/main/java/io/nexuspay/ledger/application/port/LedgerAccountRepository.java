package io.nexuspay.ledger.application.port;

import io.nexuspay.ledger.domain.LedgerAccount;

import java.util.List;
import java.util.Optional;

/**
 * Outbound port for ledger account persistence.
 */
public interface LedgerAccountRepository {

    Optional<LedgerAccount> findById(String id);

    Optional<LedgerAccount> findByNameAndCurrency(String name, String currency);

    List<LedgerAccount> findAllByTenantId(String tenantId);

    List<LedgerAccount> findAllByCurrency(String currency);

    LedgerAccount save(LedgerAccount account);

    /**
     * Updates the posted balance with optimistic locking.
     * Returns true if the update succeeded (version matched), false on conflict.
     */
    boolean updateBalanceWithVersion(String accountId, long newBalance, long expectedVersion);
}
