package io.nexuspay.reconciliation.application.service;

import io.nexuspay.reconciliation.application.port.out.LedgerQueryPort;
import io.nexuspay.reconciliation.application.port.out.LedgerQueryPort.LedgerRecord;
import io.nexuspay.reconciliation.application.port.out.PaymentQueryPort;
import io.nexuspay.reconciliation.application.port.out.PaymentQueryPort.PaymentRecord;
import io.nexuspay.reconciliation.domain.MatchResult;
import io.nexuspay.reconciliation.domain.SettlementRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-008: a settlement whose payment matches but whose ledger entry is MISSING
 * must become a tracked MISSING_LEDGER_ENTRY exception (and the settlement
 * marked EXCEPTION) — not silently dropped as "unmatched".
 */
class ThreeWayMatchingServiceTest {

    private final PaymentQueryPort payments = mock(PaymentQueryPort.class);
    private final LedgerQueryPort ledger = mock(LedgerQueryPort.class);
    private final ThreeWayMatchingService svc = new ThreeWayMatchingService(payments, ledger);

    private SettlementRecord settlement(String extId, long amount, String ccy) {
        return new SettlementRecord("sr_" + extId, "run-1", "tenant-1", "stripe",
                extId, null, amount, ccy, 0L, amount, Instant.now());
    }

    @Test
    void paymentMatchedButLedgerMissing_isPartialMissingLedgerEntry_notUnmatched() {
        var s = settlement("txn_1", 10000, "USD");
        when(payments.findByExternalRef(anyString(), anyString()))
                .thenReturn(Optional.of(new PaymentRecord("pay_1", "txn_1", 10000, "USD", "succeeded", "stripe")));
        when(ledger.findByPaymentReference(anyString())).thenReturn(Optional.empty());

        List<MatchResult> results = svc.reconcile(List.of(s));

        assertThat(results).hasSize(1);
        MatchResult r = results.get(0);
        assertThat(r.status()).isEqualTo(MatchResult.Status.PARTIAL);
        assertThat(r.exceptionType()).isEqualTo(MatchResult.ExceptionType.MISSING_LEDGER_ENTRY);
        // The settlement is flagged as an EXCEPTION (needs investigation), not UNMATCHED.
        assertThat(s.getMatchStatus()).isEqualTo("EXCEPTION");
    }

    @Test
    void fullMatch_marksMatched() {
        var s = settlement("txn_2", 5000, "EUR");
        when(payments.findByExternalRef(anyString(), anyString()))
                .thenReturn(Optional.of(new PaymentRecord("pay_2", "txn_2", 5000, "EUR", "succeeded", "stripe")));
        when(ledger.findByPaymentReference(anyString()))
                .thenReturn(Optional.of(new LedgerRecord("je_2", "pay_2", 5000, 5000, "EUR")));

        List<MatchResult> results = svc.reconcile(List.of(s));

        assertThat(results.get(0).status()).isEqualTo(MatchResult.Status.MATCHED);
        assertThat(s.getMatchStatus()).isEqualTo("MATCHED");
    }

    @Test
    void noPayment_isUnmatchedMissingPayment() {
        var s = settlement("txn_3", 700, "GBP");
        when(payments.findByExternalRef(anyString(), anyString())).thenReturn(Optional.empty());

        List<MatchResult> results = svc.reconcile(List.of(s));

        assertThat(results.get(0).status()).isEqualTo(MatchResult.Status.UNMATCHED);
        assertThat(s.getMatchStatus()).isEqualTo("UNMATCHED");
    }

    @Test
    void amountMismatch_isException() {
        var s = settlement("txn_4", 10000, "USD");
        when(payments.findByExternalRef(anyString(), anyString()))
                .thenReturn(Optional.of(new PaymentRecord("pay_4", "txn_4", 9000, "USD", "succeeded", "stripe")));

        List<MatchResult> results = svc.reconcile(List.of(s));

        assertThat(results.get(0).status()).isEqualTo(MatchResult.Status.EXCEPTION);
        assertThat(results.get(0).exceptionType()).isEqualTo(MatchResult.ExceptionType.AMOUNT_MISMATCH);
        assertThat(s.getMatchStatus()).isEqualTo("EXCEPTION");
    }
}
