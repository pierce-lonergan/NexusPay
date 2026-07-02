package io.nexuspay.b2b.adapter.out.ledger;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GAP-069 (adapter layer, {@code LedgerChargebackAdapterTest} mirror): pins tenant stamping on
 * both sides of the edge, the DR/CR composition of all three b2b postings, the L-003 canonical
 * account ids, per-currency zero-sum, and the confirmed-stub {@code external_reference} in the
 * disbursement metadata.
 */
class B2bLedgerAdapterTest {

    private static final String TENANT = "tenant-X";
    private static final long AMOUNT = 55_000L;

    private EnsureAccountsExistUseCase ensureAccounts;
    private CreateJournalEntryUseCase createJournalEntry;
    private B2bLedgerAdapter adapter;

    @BeforeEach
    void setUp() {
        ensureAccounts = mock(EnsureAccountsExistUseCase.class);
        createJournalEntry = mock(CreateJournalEntryUseCase.class);
        adapter = new B2bLedgerAdapter(ensureAccounts, createJournalEntry);
        when(createJournalEntry.execute(any())).thenAnswer(inv -> {
            CreateJournalEntryCommand cmd = inv.getArgument(0);
            List<io.nexuspay.ledger.domain.Posting> postings = cmd.postings().stream()
                    .map(p -> new io.nexuspay.ledger.domain.Posting(
                            "po_" + p.ledgerAccountId(), p.ledgerAccountId(), p.amount(), p.currency()))
                    .toList();
            return new JournalEntry("je_1", cmd.paymentReference(), cmd.description(),
                    cmd.tenantId(), Instant.now(), cmd.metadata(), postings);
        });
    }

    private CreateJournalEntryCommand captureCommand() {
        ArgumentCaptor<CreateJournalEntryCommand> captor =
                ArgumentCaptor.forClass(CreateJournalEntryCommand.class);
        verify(createJournalEntry).execute(captor.capture());
        return captor.getValue();
    }

    private void assertBalanced(List<PostingLine> postings) {
        long net = postings.stream().mapToLong(PostingLine::amount).sum();
        assertThat(net).as("postings must net to zero (balanced, L-001)").isZero();
    }

    private void assertTenantStamped(CreateJournalEntryCommand cmd) {
        verify(ensureAccounts).ensureAccountsForCurrency(eq("USD"), eq(TENANT));
        verify(ensureAccounts, never())
                .ensureAccountsForCurrency(eq("USD"), eq(EnsureAccountsExistUseCase.DEFAULT_TENANT));
        verify(ensureAccounts, never()).ensureAccountsForCurrency(eq("USD"));
        assertThat(cmd.tenantId()).isEqualTo(TENANT);
    }

    @Test
    void postInvoicePaid_drAccountsPayable_crCashClearing_tenantStamped_livemodeInMetadata() {
        adapter.postInvoicePaid(TENANT, "inv_1", AMOUNT, "USD", true);

        CreateJournalEntryCommand cmd = captureCommand();
        assertTenantStamped(cmd);
        assertThat(cmd.paymentReference()).isEqualTo("inv_1");
        assertThat(cmd.description()).isEqualTo("B2B invoice paid");
        assertThat(cmd.postings()).containsExactly(
                new PostingLine(EnsureAccountsExistUseCase.accountsPayableId("USD"), AMOUNT, "USD"),
                new PostingLine(EnsureAccountsExistUseCase.cashClearingId("USD"), -AMOUNT, "USD"));
        assertBalanced(cmd.postings());
        // WAVE1 review fix: livemode travels in metadata (marketplace-edge mirror) so sandbox
        // postings are distinguishable from live money.
        assertThat(cmd.metadata())
                .containsEntry("invoice_id", "inv_1")
                .containsEntry("livemode", true);
    }

    @Test
    void postVendorPaymentApproved_accrual_drExpense_crPayable_testModeMarked() {
        adapter.postVendorPaymentApproved(TENANT, "vp_1", AMOUNT, "USD", false);

        CreateJournalEntryCommand cmd = captureCommand();
        assertTenantStamped(cmd);
        assertThat(cmd.paymentReference()).isEqualTo("vp_1");
        assertThat(cmd.description()).isEqualTo("Vendor payment approved");
        assertThat(cmd.postings()).containsExactly(
                new PostingLine(EnsureAccountsExistUseCase.vendorExpenseId("USD"), AMOUNT, "USD"),
                new PostingLine(EnsureAccountsExistUseCase.vendorPayableId("USD"), -AMOUNT, "USD"));
        assertBalanced(cmd.postings());
        assertThat(cmd.metadata()).containsEntry("livemode", false);
    }

    @Test
    void postVendorPaymentDisbursed_settlement_drPayable_crCash_carriesExternalReference() {
        adapter.postVendorPaymentDisbursed(TENANT, "vp_1", "ref_stub_42", AMOUNT, "USD", true);

        CreateJournalEntryCommand cmd = captureCommand();
        assertTenantStamped(cmd);
        assertThat(cmd.paymentReference()).isEqualTo("vp_1");
        assertThat(cmd.description()).isEqualTo("Vendor payment disbursed");
        assertThat(cmd.postings()).containsExactly(
                new PostingLine(EnsureAccountsExistUseCase.vendorPayableId("USD"), AMOUNT, "USD"),
                new PostingLine(EnsureAccountsExistUseCase.cashClearingId("USD"), -AMOUNT, "USD"));
        assertBalanced(cmd.postings());
        // Keyed off the CONFIRMED stub result — its reference travels in metadata.
        assertThat(cmd.metadata())
                .containsEntry("external_reference", "ref_stub_42")
                .containsEntry("livemode", true);
    }

    @Test
    void lowerCaseCurrency_isNormalised() {
        adapter.postInvoicePaid(TENANT, "inv_1", AMOUNT, "usd", true);

        verify(ensureAccounts).ensureAccountsForCurrency(eq("USD"), eq(TENANT));
        CreateJournalEntryCommand cmd = captureCommand();
        assertThat(cmd.postings()).extracting(PostingLine::currency).containsOnly("USD");
        assertThat(cmd.postings()).extracting(PostingLine::ledgerAccountId).containsExactly(
                EnsureAccountsExistUseCase.accountsPayableId("USD"),
                EnsureAccountsExistUseCase.cashClearingId("USD"));
    }
}
