package io.nexuspay.dispute.adapter.out.ledger;

import io.nexuspay.ledger.application.CreateJournalEntryUseCase;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand.PostingLine;
import io.nexuspay.ledger.application.EnsureAccountsExistUseCase;
import io.nexuspay.ledger.domain.JournalEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-24 (adapter layer): pins that {@link LedgerChargebackAdapter} stamps the
 * dispute's server-authoritative tenant — NOT the hardcoded
 * {@link EnsureAccountsExistUseCase#DEFAULT_TENANT} ("default") — onto BOTH
 * sides of the money-attribution edge it owns:
 *
 * <ol>
 *   <li>account stamping: {@code ensureAccountsForCurrency(ccy, tenantId)}
 *       (LedgerChargebackAdapter.post L70); and</li>
 *   <li>the journal entry: {@code CreateJournalEntryCommand.tenantId()}
 *       (LedgerChargebackAdapter.post L75).</li>
 * </ol>
 *
 * <p>The companion service test ({@code DisputeLifecycleServiceChargebackTenantTest})
 * mocks {@link io.nexuspay.dispute.application.port.out.LedgerPort} and can only
 * pin the {@code service -> port} edge. It is GREEN against a partial revert that
 * keeps the 4-arg port signature but re-hardcodes {@code DEFAULT_TENANT} inside
 * {@code post()} — i.e. the exact layer the original bug lived on. This test
 * closes that gap: it instantiates the real adapter over mocked ledger
 * use-cases and goes RED if either L70 or L75 is reverted to {@code DEFAULT_TENANT}
 * (verified by mutating each line locally). It also asserts the postings are a
 * correct, balanced double-entry so an account-swap regression is caught too.</p>
 */
class LedgerChargebackAdapterTest {

    private static final String TENANT = "tenant-X";
    private static final String DISPUTE = "dsp_1";
    private static final long AMOUNT = 5000L;

    private EnsureAccountsExistUseCase ensureAccounts;
    private CreateJournalEntryUseCase createJournalEntry;
    private LedgerChargebackAdapter adapter;

    @BeforeEach
    void setUp() {
        ensureAccounts = mock(EnsureAccountsExistUseCase.class);
        createJournalEntry = mock(CreateJournalEntryUseCase.class);
        adapter = new LedgerChargebackAdapter(ensureAccounts, createJournalEntry);
        // The adapter logs entry.getId(); return a real balanced entry so the
        // post() path runs end-to-end and the captor sees the actual command.
        when(createJournalEntry.execute(any())).thenAnswer(this::echoEntry);
    }

    private JournalEntry echoEntry(org.mockito.invocation.InvocationOnMock inv) {
        CreateJournalEntryCommand cmd = inv.getArgument(0);
        List<io.nexuspay.ledger.domain.Posting> postings = cmd.postings().stream()
                .map(p -> new io.nexuspay.ledger.domain.Posting(
                        "po_" + p.ledgerAccountId(), p.ledgerAccountId(), p.amount(), p.currency()))
                .toList();
        return new JournalEntry("je_1", cmd.paymentReference(), cmd.description(),
                cmd.tenantId(), Instant.now(), cmd.metadata(), postings);
    }

    private CreateJournalEntryCommand captureCommand() {
        ArgumentCaptor<CreateJournalEntryCommand> captor =
                ArgumentCaptor.forClass(CreateJournalEntryCommand.class);
        verify(createJournalEntry).execute(captor.capture());
        return captor.getValue();
    }

    /** Postings must net to zero within the currency (balanced double-entry). */
    private void assertBalanced(List<PostingLine> postings) {
        long net = postings.stream().mapToLong(PostingLine::amount).sum();
        assertThat(net).as("postings must net to zero (balanced)").isZero();
    }

    @Test
    void createChargebackReserve_stampsDisputeTenant_notDefault() {
        adapter.createChargebackReserve(TENANT, DISPUTE, AMOUNT, "USD");

        // L70: account stamping scoped to the dispute's tenant, not "default".
        verify(ensureAccounts).ensureAccountsForCurrency(eq("USD"), eq(TENANT));
        verify(ensureAccounts, never())
                .ensureAccountsForCurrency(eq("USD"), eq(EnsureAccountsExistUseCase.DEFAULT_TENANT));
        // The single-arg overload coalesces to DEFAULT_TENANT — must never be used.
        verify(ensureAccounts, never()).ensureAccountsForCurrency(eq("USD"));

        // L75: the journal entry carries the dispute's tenant, not "default".
        CreateJournalEntryCommand cmd = captureCommand();
        assertThat(cmd.tenantId()).isEqualTo(TENANT);
        assertThat(cmd.tenantId()).isNotEqualTo(EnsureAccountsExistUseCase.DEFAULT_TENANT);

        // Correct, balanced reserve: DR reserve, CR merchant receivables.
        assertThat(cmd.postings()).containsExactly(
                new PostingLine(EnsureAccountsExistUseCase.chargebackReserveId("USD"), AMOUNT, "USD"),
                new PostingLine(EnsureAccountsExistUseCase.merchantReceivablesId("USD"), -AMOUNT, "USD"));
        assertBalanced(cmd.postings());
        assertThat(cmd.paymentReference()).isEqualTo(DISPUTE);
        assertThat(cmd.metadata()).containsEntry("dispute_id", DISPUTE).containsEntry("type", "chargeback");
    }

    @Test
    void reverseChargebackReserve_stampsDisputeTenant_notDefault() {
        adapter.reverseChargebackReserve(TENANT, DISPUTE, AMOUNT, "USD");

        verify(ensureAccounts).ensureAccountsForCurrency(eq("USD"), eq(TENANT));
        verify(ensureAccounts, never())
                .ensureAccountsForCurrency(eq("USD"), eq(EnsureAccountsExistUseCase.DEFAULT_TENANT));
        verify(ensureAccounts, never()).ensureAccountsForCurrency(eq("USD"));

        CreateJournalEntryCommand cmd = captureCommand();
        assertThat(cmd.tenantId()).isEqualTo(TENANT);
        assertThat(cmd.tenantId()).isNotEqualTo(EnsureAccountsExistUseCase.DEFAULT_TENANT);

        // Reversal: DR merchant receivables, CR reserve.
        assertThat(cmd.postings()).containsExactly(
                new PostingLine(EnsureAccountsExistUseCase.merchantReceivablesId("USD"), AMOUNT, "USD"),
                new PostingLine(EnsureAccountsExistUseCase.chargebackReserveId("USD"), -AMOUNT, "USD"));
        assertBalanced(cmd.postings());
    }

    @Test
    void finaliseChargebackExpense_stampsDisputeTenant_notDefault() {
        adapter.finaliseChargebackExpense(TENANT, DISPUTE, AMOUNT, "USD");

        verify(ensureAccounts).ensureAccountsForCurrency(eq("USD"), eq(TENANT));
        verify(ensureAccounts, never())
                .ensureAccountsForCurrency(eq("USD"), eq(EnsureAccountsExistUseCase.DEFAULT_TENANT));
        verify(ensureAccounts, never()).ensureAccountsForCurrency(eq("USD"));

        CreateJournalEntryCommand cmd = captureCommand();
        assertThat(cmd.tenantId()).isEqualTo(TENANT);
        assertThat(cmd.tenantId()).isNotEqualTo(EnsureAccountsExistUseCase.DEFAULT_TENANT);

        // Loss: DR chargeback expense, CR reserve.
        assertThat(cmd.postings()).containsExactly(
                new PostingLine(EnsureAccountsExistUseCase.chargebackExpenseId("USD"), AMOUNT, "USD"),
                new PostingLine(EnsureAccountsExistUseCase.chargebackReserveId("USD"), -AMOUNT, "USD"));
        assertBalanced(cmd.postings());
    }

    /**
     * The adapter upper-cases the currency before stamping accounts and building
     * postings; a lower-case input must still scope to the dispute's tenant and
     * the canonical (upper-case) account ids — never "default".
     */
    @Test
    void lowerCaseCurrency_isNormalised_andTenantStillStamped() {
        adapter.createChargebackReserve(TENANT, DISPUTE, AMOUNT, "usd");

        verify(ensureAccounts).ensureAccountsForCurrency(eq("USD"), eq(TENANT));

        CreateJournalEntryCommand cmd = captureCommand();
        assertThat(cmd.tenantId()).isEqualTo(TENANT);
        assertThat(cmd.postings()).extracting(PostingLine::currency).containsOnly("USD");
        assertThat(cmd.postings()).extracting(PostingLine::ledgerAccountId)
                .containsExactly(
                        EnsureAccountsExistUseCase.chargebackReserveId("USD"),
                        EnsureAccountsExistUseCase.merchantReceivablesId("USD"));
    }
}
