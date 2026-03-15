package io.nexuspay.ledger.application;

import io.nexuspay.common.id.PrefixedId;
import io.nexuspay.ledger.application.port.LedgerAccountRepository;
import io.nexuspay.ledger.domain.AccountType;
import io.nexuspay.ledger.domain.LedgerAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Ensures the default set of accounts exists for a given currency.
 * Used when a payment in a new currency arrives — auto-creates the account set.
 * Account creation is idempotent.
 */
@Service
public class EnsureAccountsExistUseCase {

    private static final Logger log = LoggerFactory.getLogger(EnsureAccountsExistUseCase.class);

    private static final Map<String, AccountType> DEFAULT_ACCOUNTS = Map.of(
            "Merchant Receivables", AccountType.ASSET,
            "Customer Liability", AccountType.LIABILITY,
            "Revenue", AccountType.REVENUE,
            "Processing Fees", AccountType.EXPENSE,
            "Refunds", AccountType.EXPENSE
    );

    private final LedgerAccountRepository ledgerAccountRepository;

    public EnsureAccountsExistUseCase(LedgerAccountRepository ledgerAccountRepository) {
        this.ledgerAccountRepository = ledgerAccountRepository;
    }

    @Transactional
    public void ensureAccountsForCurrency(String currencyCode) {
        String currency = currencyCode.toUpperCase();

        for (var entry : DEFAULT_ACCOUNTS.entrySet()) {
            String name = entry.getKey() + " (" + currency + ")";
            if (ledgerAccountRepository.findByNameAndCurrency(name, currency).isEmpty()) {
                String id = buildAccountId(entry.getKey(), currency);
                var account = new LedgerAccount(
                        id, name, entry.getValue(), currency,
                        0L, 0L, "default",
                        Instant.now(), Instant.now()
                );
                ledgerAccountRepository.save(account);
                log.info("Created ledger account: {} ({})", name, id);
            }
        }
    }

    private String buildAccountId(String accountName, String currency) {
        String slug = accountName.toLowerCase()
                .replace(" ", "_")
                .replace("(", "")
                .replace(")", "");
        return "la_" + slug + "_" + currency.toLowerCase();
    }
}
