package io.nexuspay.ledger.application;

import io.nexuspay.common.exception.LedgerException;
import io.nexuspay.ledger.application.port.LedgerAccountRepository;
import io.nexuspay.ledger.domain.LedgerAccount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reads posted_balance directly from the ledger_accounts table.
 * No Valkey cache in Phase 1 — direct DB read is sufficient.
 */
@Service
@Transactional(readOnly = true)
public class GetBalanceUseCase {

    private final LedgerAccountRepository ledgerAccountRepository;

    public GetBalanceUseCase(LedgerAccountRepository ledgerAccountRepository) {
        this.ledgerAccountRepository = ledgerAccountRepository;
    }

    public LedgerAccount getBalance(String accountId) {
        return ledgerAccountRepository.findById(accountId)
                .orElseThrow(() -> LedgerException.accountNotFound(accountId));
    }

    public List<LedgerAccount> getAllBalances(String tenantId) {
        return ledgerAccountRepository.findAllByTenantId(tenantId);
    }

    public List<LedgerAccount> getBalancesByCurrency(String currency) {
        return ledgerAccountRepository.findAllByCurrency(currency);
    }
}
