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
import java.util.List;

/**
 * Ensures the default set of accounts exists for a given currency.
 * Used when a payment in a new currency arrives — auto-creates the account set.
 * Account creation is idempotent.
 *
 * <p>This class is the single source of truth for chart-of-accounts IDs. The
 * short keys MUST stay in sync with {@code V2__seed_default_accounts.sql}
 * (e.g. {@code la_merchant_recv_usd}).</p>
 */
@Service
public class EnsureAccountsExistUseCase {

    private static final Logger log = LoggerFactory.getLogger(EnsureAccountsExistUseCase.class);

    public static final String DEFAULT_TENANT = "default";

    private record AccountSpec(String name, String shortKey, AccountType type) {}

    private static final List<AccountSpec> DEFAULT_ACCOUNTS = List.of(
            new AccountSpec("Merchant Receivables", "merchant_recv", AccountType.ASSET),
            new AccountSpec("Customer Liability", "customer_liab", AccountType.LIABILITY),
            new AccountSpec("Revenue", "revenue", AccountType.REVENUE),
            new AccountSpec("Processing Fees", "processing_fees", AccountType.EXPENSE),
            new AccountSpec("Refunds", "refunds", AccountType.EXPENSE),
            new AccountSpec("Chargeback Reserve", "chargeback_reserve", AccountType.LIABILITY),
            new AccountSpec("Chargeback Expense", "chargeback_expense", AccountType.EXPENSE)
    );

    private final LedgerAccountRepository ledgerAccountRepository;

    public EnsureAccountsExistUseCase(LedgerAccountRepository ledgerAccountRepository) {
        this.ledgerAccountRepository = ledgerAccountRepository;
    }

    @Transactional
    public void ensureAccountsForCurrency(String currencyCode) {
        ensureAccountsForCurrency(currencyCode, DEFAULT_TENANT);
    }

    @Transactional
    public void ensureAccountsForCurrency(String currencyCode, String tenantId) {
        String currency = currencyCode.toUpperCase();
        String tenant = (tenantId == null || tenantId.isBlank()) ? DEFAULT_TENANT : tenantId;

        for (AccountSpec spec : DEFAULT_ACCOUNTS) {
            String id = accountId(spec.shortKey(), currency);
            if (ledgerAccountRepository.findById(id).isEmpty()) {
                String name = spec.name() + " (" + currency + ")";
                var account = new LedgerAccount(
                        id, name, spec.type(), currency,
                        0L, 0L, tenant,
                        Instant.now(), Instant.now()
                );
                ledgerAccountRepository.save(account);
                log.info("Created ledger account: {} ({})", name, id);
            }
        }
    }

    /** Canonical account-id builder: {@code la_{shortKey}_{ccy}}. */
    public static String accountId(String shortKey, String currency) {
        return "la_" + shortKey + "_" + currency.toLowerCase();
    }

    public static String merchantReceivablesId(String currency) { return accountId("merchant_recv", currency); }
    public static String customerLiabilityId(String currency) { return accountId("customer_liab", currency); }
    public static String revenueId(String currency) { return accountId("revenue", currency); }
    public static String processingFeesId(String currency) { return accountId("processing_fees", currency); }
    public static String refundsId(String currency) { return accountId("refunds", currency); }
    public static String fxClearingId(String currency) { return accountId("fx_clearing", currency); }
    public static String chargebackReserveId(String currency) { return accountId("chargeback_reserve", currency); }
    public static String chargebackExpenseId(String currency) { return accountId("chargeback_expense", currency); }
}
