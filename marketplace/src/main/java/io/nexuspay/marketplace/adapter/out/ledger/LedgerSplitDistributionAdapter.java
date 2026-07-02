package io.nexuspay.marketplace.adapter.out.ledger;

import io.nexuspay.ledger.application.CreateJournalEntryUseCase;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand.PostingLine;
import io.nexuspay.ledger.application.EnsureAccountsExistUseCase;
import io.nexuspay.marketplace.application.port.out.LedgerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements {@link LedgerPort} by booking the split-distribution journal entry through the
 * ledger module's {@link CreateJournalEntryUseCase} — the SEC-24 dispute→ledger pattern
 * ({@code LedgerChargebackAdapter}) applied to the marketplace edge (GAP-063).
 *
 * <p><b>Atomicity:</b> {@code CreateJournalEntryUseCase.execute} is {@code @Transactional} with
 * default REQUIRED propagation, so it JOINS the caller's (SplitPaymentWriter's) transaction. There
 * is deliberately NO try/catch here: a posting failure propagates and rolls the whole split
 * creation back (the ledger is the money truth, never best-effort).</p>
 *
 * @since GAP-063 (WAVE1-money-ledger)
 */
@Component
public class LedgerSplitDistributionAdapter implements LedgerPort {

    private static final Logger log = LoggerFactory.getLogger(LedgerSplitDistributionAdapter.class);

    /** Transition-name half of the (payment_reference, description) idempotency key (V4028). */
    static final String DESC_SPLIT_CREATED = "Split payment created";

    private final EnsureAccountsExistUseCase ensureAccounts;
    private final CreateJournalEntryUseCase createJournalEntry;

    public LedgerSplitDistributionAdapter(EnsureAccountsExistUseCase ensureAccounts,
                                          CreateJournalEntryUseCase createJournalEntry) {
        this.ensureAccounts = ensureAccounts;
        this.createJournalEntry = createJournalEntry;
    }

    @Override
    public void postSplitDistribution(String tenantId, String splitPaymentId, String paymentId,
                                      String currency, List<Leg> legs, long platformFeeAmount,
                                      boolean livemode) {
        String ccy = currency.toUpperCase();
        // Auto-creates the chart-of-accounts rows idempotently. The journal entry/postings below are
        // scoped to the caller's server-authoritative tenant (SEC-24 pattern); the PLATFORM-SHARED
        // per-currency singleton accounts themselves are stamped DEFAULT_TENANT inside the use case
        // (WAVE1 blocker fix — a caller-tenant stamp would expose the cross-tenant aggregate balance
        // to that tenant's GET /v1/ledger/accounts).
        ensureAccounts.ensureAccountsForCurrency(ccy, tenantId);

        // CR one connected-payable line per leg; CR the fee-revenue line when a fee applies. The DR
        // (platform clearing) is composed as the SUM of the credits, so the entry balances by
        // construction even when FIXED rules under-allocate the payment total (L-001 zero-sum).
        //
        // WAVE1 review fix: a leg CAN legitimately resolve to 0 (a REMAINDER rule after the other
        // rules consume the whole distributable amount, a FIXED 0 rule, or HALF_EVEN rounding), and
        // Posting's constructor rejects amount == 0. A 0-amount leg moves NO money, so it emits NO
        // posting line — its identity is still recorded in the metadata legs array. The entry stays
        // balanced by construction (the DR is the sum of the EMITTED credits).
        List<PostingLine> postings = new ArrayList<>();
        List<Map<String, Object>> legMeta = new ArrayList<>();
        long creditTotal = 0;
        for (Leg leg : legs) {
            legMeta.add(Map.of(
                    "connected_account_id", leg.connectedAccountId(),
                    "amount", leg.amount()));
            if (leg.amount() == 0) {
                continue; // no money movement — no posting line (Posting rejects 0)
            }
            creditTotal += leg.amount();
            postings.add(new PostingLine(
                    EnsureAccountsExistUseCase.connectedPayableId(ccy), -leg.amount(), ccy));
        }
        if (platformFeeAmount > 0) {
            creditTotal += platformFeeAmount;
            postings.add(new PostingLine(
                    EnsureAccountsExistUseCase.platformFeeRevenueId(ccy), -platformFeeAmount, ccy));
        }
        if (creditTotal == 0) {
            // Degenerate all-zero distribution (every leg 0, no fee): zero money moved, so there is
            // honestly nothing to book — and a zero-sum entry with 0-amount lines is unconstructible
            // anyway. Skipping keeps previously-valid split creation working (fail-closed would be a
            // functional regression on valid input, not a money-safety gain: no state advances
            // without its money record, because there IS no money).
            log.info("Split {} (payment {}) distributes zero money in {} — no journal entry booked",
                    splitPaymentId, paymentId, ccy);
            return;
        }
        postings.add(0, new PostingLine(
                EnsureAccountsExistUseCase.platformClearingId(ccy), creditTotal, ccy));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("split_id", splitPaymentId);
        metadata.put("payment_id", paymentId);
        metadata.put("livemode", livemode);
        metadata.put("legs", legMeta);
        metadata.put("type", "split_payment");

        var command = new CreateJournalEntryCommand(
                splitPaymentId,
                DESC_SPLIT_CREATED,
                tenantId,
                metadata,
                postings
        );

        var entry = createJournalEntry.execute(command);
        log.info("Split-distribution ledger entry {} booked: {} legs + fee {} {} for split {} (payment {})",
                entry.getId(), legs.size(), platformFeeAmount, ccy, splitPaymentId, paymentId);
    }
}
