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

    /**
     * @param platformShared WAVE1 cross-tenant-exposure fix: a platform-shared account is a
     *                       per-currency SINGLETON row whose {@code posted_balance} aggregates money
     *                       across ALL tenants (its id has no tenant component). Such a row must
     *                       NEVER be stamped with a caller tenant — first-writer-wins stamping would
     *                       surface every tenant's aggregated balance to whichever tenant happened to
     *                       transact first (via {@code GET /v1/ledger/accounts} →
     *                       {@code findAllByTenantId}). Platform-shared accounts are always stamped
     *                       {@link #DEFAULT_TENANT}, so they are invisible to every real tenant's
     *                       balance read; per-tenant positions remain derivable from the
     *                       tenant-scoped journal entries.
     */
    private record AccountSpec(String name, String shortKey, AccountType type, boolean platformShared) {}

    private static final List<AccountSpec> DEFAULT_ACCOUNTS = List.of(
            new AccountSpec("Merchant Receivables", "merchant_recv", AccountType.ASSET, false),
            new AccountSpec("Customer Liability", "customer_liab", AccountType.LIABILITY, false),
            new AccountSpec("Revenue", "revenue", AccountType.REVENUE, false),
            new AccountSpec("Processing Fees", "processing_fees", AccountType.EXPENSE, false),
            new AccountSpec("Refunds", "refunds", AccountType.EXPENSE, false),
            new AccountSpec("Chargeback Reserve", "chargeback_reserve", AccountType.LIABILITY, false),
            new AccountSpec("Chargeback Expense", "chargeback_expense", AccountType.EXPENSE, false),
            // GAP-063 (marketplace split-payment postings): platform money-in clearing, the per-currency
            // connected-merchant payable, and the platform's fee revenue. Auto-created idempotently at
            // runtime — the chargeback accounts above are the precedent (no seed migration required).
            // platformShared=true: these singletons aggregate every tenant's splits — DEFAULT_TENANT only.
            new AccountSpec("Platform Clearing", "platform_clearing", AccountType.ASSET, true),
            new AccountSpec("Connected Merchant Payable", "connected_payable", AccountType.LIABILITY, true),
            new AccountSpec("Platform Fee Revenue", "platform_fee_revenue", AccountType.REVENUE, true),
            // GAP-069 (b2b invoice / vendor-payment postings): AP settlement, cash clearing, and the
            // vendor accrual pair (expense recognized at approval, payable settled at disbursement).
            // platformShared=true for the same cross-tenant-aggregation reason as the GAP-063 set.
            new AccountSpec("Accounts Payable", "accounts_payable", AccountType.LIABILITY, true),
            new AccountSpec("Cash Clearing", "cash_clearing", AccountType.ASSET, true),
            new AccountSpec("Vendor Payable", "vendor_payable", AccountType.LIABILITY, true),
            new AccountSpec("Vendor Payment Expense", "vendor_expense", AccountType.EXPENSE, true)
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

        // NOTE (WAVE1): this is a check-then-act (findById + save). Two transactions racing on a
        // currency's FIRST use can both miss the read; the loser's INSERT then violates the
        // ledger_accounts PK at flush and its whole (money) transaction rolls back — correctly, with
        // zero partial state — and the retry succeeds because the row now exists. The dup-key catch
        // helpers in the money paths (CreateJournalEntryUseCase / SplitPaymentService) are narrowed to
        // their OWN constraint names, so this PK race is never misclassified as a benign idempotency
        // duplicate.
        for (AccountSpec spec : DEFAULT_ACCOUNTS) {
            String id = accountId(spec.shortKey(), currency);
            if (ledgerAccountRepository.findById(id).isEmpty()) {
                String name = spec.name() + " (" + currency + ")";
                // Platform-shared singletons are ALWAYS stamped DEFAULT_TENANT (see AccountSpec):
                // stamping the caller's tenant would expose the cross-tenant aggregate balance to
                // that tenant's GET /v1/ledger/accounts (first-writer-wins).
                String rowTenant = spec.platformShared() ? DEFAULT_TENANT : tenant;
                var account = new LedgerAccount(
                        id, name, spec.type(), currency,
                        0L, 0L, rowTenant,
                        Instant.now(), Instant.now()
                );
                ledgerAccountRepository.save(account);
                log.info("Created ledger account: {} ({}, tenant={})", name, id, rowTenant);
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

    // GAP-063: marketplace split-payment accounts (L-003 canonical ids).
    public static String platformClearingId(String currency) { return accountId("platform_clearing", currency); }
    public static String connectedPayableId(String currency) { return accountId("connected_payable", currency); }
    public static String platformFeeRevenueId(String currency) { return accountId("platform_fee_revenue", currency); }

    // GAP-069: b2b invoice / vendor-payment accounts (L-003 canonical ids).
    public static String accountsPayableId(String currency) { return accountId("accounts_payable", currency); }
    public static String cashClearingId(String currency) { return accountId("cash_clearing", currency); }
    public static String vendorPayableId(String currency) { return accountId("vendor_payable", currency); }
    public static String vendorExpenseId(String currency) { return accountId("vendor_expense", currency); }
}
