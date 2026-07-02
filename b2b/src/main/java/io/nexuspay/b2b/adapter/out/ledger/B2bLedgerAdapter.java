package io.nexuspay.b2b.adapter.out.ledger;

import io.nexuspay.b2b.application.port.out.LedgerPort;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand.PostingLine;
import io.nexuspay.ledger.application.EnsureAccountsExistUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements the b2b {@link LedgerPort} by booking journal entries through the ledger module's
 * {@link CreateJournalEntryUseCase} — the SEC-24 dispute→ledger pattern
 * ({@code LedgerChargebackAdapter}) applied to the b2b edge (GAP-069).
 *
 * <p><b>Atomicity:</b> {@code CreateJournalEntryUseCase.execute} joins the caller's transaction
 * (REQUIRED propagation). Deliberately NO try/catch, no {@code @Async}, no REQUIRES_NEW: a posting
 * failure propagates and rolls the money-state transition back with it.</p>
 *
 * @since GAP-069 (WAVE1-money-ledger)
 */
@Component
public class B2bLedgerAdapter implements LedgerPort {

    private static final Logger log = LoggerFactory.getLogger(B2bLedgerAdapter.class);

    // Transition-name halves of the (payment_reference, description) idempotency keys (V4028).
    static final String DESC_INVOICE_PAID = "B2B invoice paid";
    static final String DESC_VENDOR_PAYMENT_APPROVED = "Vendor payment approved";
    static final String DESC_VENDOR_PAYMENT_DISBURSED = "Vendor payment disbursed";

    private final EnsureAccountsExistUseCase ensureAccounts;
    private final CreateJournalEntryUseCase createJournalEntry;

    public B2bLedgerAdapter(EnsureAccountsExistUseCase ensureAccounts,
                            CreateJournalEntryUseCase createJournalEntry) {
        this.ensureAccounts = ensureAccounts;
        this.createJournalEntry = createJournalEntry;
    }

    @Override
    public void postInvoicePaid(String tenantId, String invoiceId, long totalAmount, String currency,
                                boolean livemode) {
        // DR Accounts Payable, CR Cash Clearing — settle the invoice liability.
        post(tenantId, invoiceId, DESC_INVOICE_PAID, currency,
                EnsureAccountsExistUseCase.accountsPayableId(currency.toUpperCase()), totalAmount,
                EnsureAccountsExistUseCase.cashClearingId(currency.toUpperCase()), -totalAmount,
                metadata(livemode, "invoice_id", invoiceId, "type", "b2b_invoice"));
    }

    @Override
    public void postVendorPaymentApproved(String tenantId, String paymentId, long amount, String currency,
                                          boolean livemode) {
        // DR Vendor Payment Expense, CR Vendor Payable — the accrual at approval.
        post(tenantId, paymentId, DESC_VENDOR_PAYMENT_APPROVED, currency,
                EnsureAccountsExistUseCase.vendorExpenseId(currency.toUpperCase()), amount,
                EnsureAccountsExistUseCase.vendorPayableId(currency.toUpperCase()), -amount,
                metadata(livemode, "vendor_payment_id", paymentId, "type", "vendor_payment"));
    }

    @Override
    public void postVendorPaymentDisbursed(String tenantId, String paymentId, String externalReference,
                                           long amount, String currency, boolean livemode) {
        // DR Vendor Payable, CR Cash Clearing — the settlement, keyed off the CONFIRMED stub result.
        post(tenantId, paymentId, DESC_VENDOR_PAYMENT_DISBURSED, currency,
                EnsureAccountsExistUseCase.vendorPayableId(currency.toUpperCase()), amount,
                EnsureAccountsExistUseCase.cashClearingId(currency.toUpperCase()), -amount,
                metadata(livemode, "vendor_payment_id", paymentId, "type", "vendor_payment",
                        "external_reference", externalReference));
    }

    /**
     * WAVE1 review fix: every b2b entry carries {@code livemode} in metadata (the marketplace
     * {@code LedgerSplitDistributionAdapter} mirror) — an sk_test_ admin/operator key CAN drive
     * these transitions, and its postings must be distinguishable from live money.
     */
    private static Map<String, Object> metadata(boolean livemode, Object... kv) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            metadata.put((String) kv[i], kv[i + 1]);
        }
        metadata.put("livemode", livemode);
        return metadata;
    }

    private void post(String tenantId, String reference, String description, String currency,
                      String debitAccount, long debitAmount,
                      String creditAccount, long creditAmount,
                      Map<String, Object> metadata) {
        String ccy = currency.toUpperCase();
        // Auto-creates the chart-of-accounts rows idempotently. The journal entry/postings below are
        // scoped to the caller's server-authoritative tenant (SEC-24 pattern); the PLATFORM-SHARED
        // per-currency singleton accounts themselves are stamped DEFAULT_TENANT inside the use case
        // (WAVE1 blocker fix — a caller-tenant stamp would expose the cross-tenant aggregate balance
        // to that tenant's GET /v1/ledger/accounts).
        ensureAccounts.ensureAccountsForCurrency(ccy, tenantId);

        var command = new CreateJournalEntryCommand(
                reference,
                description,
                tenantId,
                metadata,
                List.of(
                        new PostingLine(debitAccount, debitAmount, ccy),
                        new PostingLine(creditAccount, creditAmount, ccy)
                )
        );

        var entry = createJournalEntry.execute(command);
        log.info("B2B ledger entry {} booked: {} {} {} for {}",
                entry.getId(), description, debitAmount, ccy, reference);
    }
}
