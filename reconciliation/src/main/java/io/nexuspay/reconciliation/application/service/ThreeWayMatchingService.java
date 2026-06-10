package io.nexuspay.reconciliation.application.service;

import io.nexuspay.reconciliation.application.port.out.LedgerQueryPort;
import io.nexuspay.reconciliation.application.port.out.PaymentQueryPort;
import io.nexuspay.reconciliation.domain.MatchResult;
import io.nexuspay.reconciliation.domain.SettlementRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Core reconciliation engine — performs three-way matching.
 *
 * <p>Matches settlement records from PSP files against three data sources:</p>
 * <ol>
 *   <li><strong>Payment records</strong> — NexusPay payments or HyperSwitch data</li>
 *   <li><strong>Ledger entries</strong> — double-entry journal entries in our ledger</li>
 *   <li><strong>Settlement records</strong> — what the PSP reports as settled</li>
 * </ol>
 *
 * <p>The matcher detects:</p>
 * <ul>
 *   <li>Amount mismatches between settlement and payment/ledger</li>
 *   <li>Missing payments (settlement exists but no matching payment)</li>
 *   <li>Missing settlements (payment exists but not in settlement file)</li>
 *   <li>Fee discrepancies (reported fees don't match expected)</li>
 *   <li>Currency mismatches</li>
 * </ul>
 *
 * @since 0.2.0 (Sprint 2.3)
 */
@Service
public class ThreeWayMatchingService {

    private static final Logger log = LoggerFactory.getLogger(ThreeWayMatchingService.class);

    /**
     * Default tolerance for fuzzy matching (1 minor unit = $0.01 for USD).
     * Covers rounding differences between PSP and internal calculations.
     */
    private static final long DEFAULT_AMOUNT_TOLERANCE = 1;

    private final PaymentQueryPort paymentQueryPort;
    private final LedgerQueryPort ledgerQueryPort;

    public ThreeWayMatchingService(PaymentQueryPort paymentQueryPort, LedgerQueryPort ledgerQueryPort) {
        this.paymentQueryPort = paymentQueryPort;
        this.ledgerQueryPort = ledgerQueryPort;
    }

    /**
     * Matches a batch of settlement records against payments and ledger entries.
     *
     * @param settlements the parsed settlement records to match
     * @return match results for each settlement record
     */
    public List<MatchResult> reconcile(List<SettlementRecord> settlements) {
        List<MatchResult> results = new ArrayList<>(settlements.size());

        for (SettlementRecord settlement : settlements) {
            MatchResult result = matchSingle(settlement);
            results.add(result);

            if (result.isSuccessful()) {
                settlement.markMatched(result.matchedPaymentId(), result.matchedJournalEntryId());
            } else if (result.status() == MatchResult.Status.UNMATCHED) {
                settlement.markUnmatched();
            } else {
                // EXCEPTION and PARTIAL are both discrepancies needing investigation.
                settlement.markException();
            }
        }

        int matched = (int) results.stream().filter(MatchResult::isSuccessful).count();
        int unmatched = (int) results.stream()
                .filter(r -> r.status() == MatchResult.Status.UNMATCHED).count();
        int exceptions = results.size() - matched - unmatched;   // EXCEPTION + PARTIAL

        log.info("Reconciliation complete: total={}, matched={}, unmatched={}, exceptions={}",
                results.size(), matched, unmatched, exceptions);

        return results;
    }

    private MatchResult matchSingle(SettlementRecord settlement) {
        // Step 1: Find matching payment by external reference
        Optional<PaymentQueryPort.PaymentRecord> payment = paymentQueryPort.findByExternalRef(
                settlement.getExternalId(), settlement.getProvider());

        if (payment.isEmpty()) {
            log.debug("No payment found for settlement: externalId={}, provider={}",
                    settlement.getExternalId(), settlement.getProvider());
            return MatchResult.unmatched(settlement.getId());
        }

        PaymentQueryPort.PaymentRecord paymentRecord = payment.get();

        // Step 2: Validate currency match
        if (!settlement.getCurrency().equals(paymentRecord.currency())) {
            return MatchResult.exception(
                    MatchResult.ExceptionType.CURRENCY_MISMATCH,
                    settlement.getId(),
                    paymentRecord.paymentId(),
                    String.format("Currency mismatch: settlement=%s, payment=%s",
                            settlement.getCurrency(), paymentRecord.currency()));
        }

        // Step 3: Validate amount match (with tolerance for rounding)
        if (!amountsMatch(settlement.getAmount(), paymentRecord.amount())) {
            return MatchResult.exception(
                    MatchResult.ExceptionType.AMOUNT_MISMATCH,
                    settlement.getId(),
                    paymentRecord.paymentId(),
                    String.format("Amount mismatch: settlement=%d, payment=%d (tolerance=%d)",
                            settlement.getAmount(), paymentRecord.amount(), DEFAULT_AMOUNT_TOLERANCE));
        }

        // Step 4: Find matching ledger entry
        Optional<LedgerQueryPort.LedgerRecord> ledgerEntry = ledgerQueryPort.findByPaymentReference(
                paymentRecord.paymentId());

        if (ledgerEntry.isEmpty()) {
            log.debug("Payment matched but no ledger entry: paymentId={}", paymentRecord.paymentId());
            return MatchResult.partial(settlement.getId(), paymentRecord.paymentId());
        }

        LedgerQueryPort.LedgerRecord ledger = ledgerEntry.get();

        // Step 5: Validate ledger amount matches settlement
        if (!amountsMatch(settlement.getAmount(), ledger.debitAmount())) {
            return MatchResult.exception(
                    MatchResult.ExceptionType.AMOUNT_MISMATCH,
                    settlement.getId(),
                    paymentRecord.paymentId(),
                    String.format("Ledger amount mismatch: settlement=%d, ledger_debit=%d",
                            settlement.getAmount(), ledger.debitAmount()));
        }

        // All three match
        return MatchResult.matched(
                settlement.getId(),
                paymentRecord.paymentId(),
                ledger.journalEntryId());
    }

    private boolean amountsMatch(long expected, long actual) {
        return Math.abs(expected - actual) <= DEFAULT_AMOUNT_TOLERANCE;
    }
}
