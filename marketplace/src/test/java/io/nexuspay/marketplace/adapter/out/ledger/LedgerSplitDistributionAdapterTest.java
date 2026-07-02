package io.nexuspay.marketplace.adapter.out.ledger;

import io.nexuspay.ledger.application.CreateJournalEntryUseCase;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand.PostingLine;
import io.nexuspay.ledger.application.EnsureAccountsExistUseCase;
import io.nexuspay.ledger.domain.JournalEntry;
import io.nexuspay.marketplace.application.port.out.LedgerPort;
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
 * GAP-063 (adapter layer, {@code LedgerChargebackAdapterTest} mirror): pins that
 * {@link LedgerSplitDistributionAdapter} stamps the caller's server-authoritative tenant on BOTH
 * sides of the money-attribution edge (account stamping + the journal entry), composes a correct
 * per-currency zero-sum entry (DR platform clearing == sum of leg credits + fee), uses the L-003
 * canonical account ids, and keys idempotency by (splitId, "Split payment created").
 */
class LedgerSplitDistributionAdapterTest {

    private static final String TENANT = "tenant-X";
    private static final String SPLIT = "sp_1";
    private static final String PAYMENT = "pi_1";

    private EnsureAccountsExistUseCase ensureAccounts;
    private CreateJournalEntryUseCase createJournalEntry;
    private LedgerSplitDistributionAdapter adapter;

    @BeforeEach
    void setUp() {
        ensureAccounts = mock(EnsureAccountsExistUseCase.class);
        createJournalEntry = mock(CreateJournalEntryUseCase.class);
        adapter = new LedgerSplitDistributionAdapter(ensureAccounts, createJournalEntry);
        // The adapter logs entry.getId(); return a real balanced entry so the post path runs
        // end-to-end and the captor sees the actual command (JournalEntry re-validates zero-sum).
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

    /** Postings must net to zero within the currency (balanced double-entry, L-001). */
    private void assertBalanced(List<PostingLine> postings) {
        long net = postings.stream().mapToLong(PostingLine::amount).sum();
        assertThat(net).as("postings must net to zero (balanced)").isZero();
    }

    @Test
    void multiLegWithFee_composesBalancedEntry_withCanonicalAccounts_andTenantStamped() {
        adapter.postSplitDistribution(TENANT, SPLIT, PAYMENT, "USD",
                List.of(new LedgerPort.Leg("ca_a", 6000L), new LedgerPort.Leg("ca_b", 2500L)),
                1500L, true);

        // Account stamping scoped to the caller's tenant, never the DEFAULT_TENANT coalesce.
        verify(ensureAccounts).ensureAccountsForCurrency(eq("USD"), eq(TENANT));
        verify(ensureAccounts, never())
                .ensureAccountsForCurrency(eq("USD"), eq(EnsureAccountsExistUseCase.DEFAULT_TENANT));
        verify(ensureAccounts, never()).ensureAccountsForCurrency(eq("USD"));

        CreateJournalEntryCommand cmd = captureCommand();
        assertThat(cmd.tenantId()).isEqualTo(TENANT);
        assertThat(cmd.paymentReference()).isEqualTo(SPLIT);
        assertThat(cmd.description()).isEqualTo("Split payment created");

        // DR platform clearing == sum(legs) + fee; one CR per leg; CR fee revenue.
        assertThat(cmd.postings()).containsExactly(
                new PostingLine(EnsureAccountsExistUseCase.platformClearingId("USD"), 10000L, "USD"),
                new PostingLine(EnsureAccountsExistUseCase.connectedPayableId("USD"), -6000L, "USD"),
                new PostingLine(EnsureAccountsExistUseCase.connectedPayableId("USD"), -2500L, "USD"),
                new PostingLine(EnsureAccountsExistUseCase.platformFeeRevenueId("USD"), -1500L, "USD"));
        assertBalanced(cmd.postings());

        // Leg identity + livemode travel in metadata (accounts are per-currency singletons).
        assertThat(cmd.metadata())
                .containsEntry("split_id", SPLIT)
                .containsEntry("payment_id", PAYMENT)
                .containsEntry("livemode", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legs = (List<Map<String, Object>>) cmd.metadata().get("legs");
        assertThat(legs).containsExactly(
                Map.of("connected_account_id", "ca_a", "amount", 6000L),
                Map.of("connected_account_id", "ca_b", "amount", 2500L));
    }

    @Test
    void zeroFee_omitsTheFeeRevenueLine() {
        adapter.postSplitDistribution(TENANT, SPLIT, PAYMENT, "USD",
                List.of(new LedgerPort.Leg("ca_a", 10000L)), 0L, false);

        CreateJournalEntryCommand cmd = captureCommand();
        assertThat(cmd.postings()).containsExactly(
                new PostingLine(EnsureAccountsExistUseCase.platformClearingId("USD"), 10000L, "USD"),
                new PostingLine(EnsureAccountsExistUseCase.connectedPayableId("USD"), -10000L, "USD"));
        assertBalanced(cmd.postings());
        assertThat(cmd.metadata()).containsEntry("livemode", false);
    }

    @Test
    void underAllocatedFixedSplit_stillBalances_debitComposedFromCredits() {
        // FIXED rules that under-allocate the payment total: the DR is composed as the SUM of the
        // credits (7000 + 500 fee = 7500), NOT the payment total — balanced by construction.
        adapter.postSplitDistribution(TENANT, SPLIT, PAYMENT, "USD",
                List.of(new LedgerPort.Leg("ca_a", 7000L)), 500L, true);

        CreateJournalEntryCommand cmd = captureCommand();
        assertThat(cmd.postings()).containsExactly(
                new PostingLine(EnsureAccountsExistUseCase.platformClearingId("USD"), 7500L, "USD"),
                new PostingLine(EnsureAccountsExistUseCase.connectedPayableId("USD"), -7000L, "USD"),
                new PostingLine(EnsureAccountsExistUseCase.platformFeeRevenueId("USD"), -500L, "USD"));
        assertBalanced(cmd.postings());
    }

    @Test
    void zeroAmountLeg_emitsNoPostingLine_keepsLegIdentityInMetadata_andStillBalances() {
        // WAVE1 review fix: [PERCENTAGE 100, REMAINDER] resolves the REMAINDER leg to 0 — valid per
        // validateSplitRules and previously creatable. A 0-amount leg must not produce a PostingLine
        // (Posting rejects 0) but its identity must survive in metadata.
        adapter.postSplitDistribution(TENANT, SPLIT, PAYMENT, "USD",
                List.of(new LedgerPort.Leg("ca_a", 10000L), new LedgerPort.Leg("ca_b", 0L)),
                0L, true);

        CreateJournalEntryCommand cmd = captureCommand();
        assertThat(cmd.postings()).containsExactly(
                new PostingLine(EnsureAccountsExistUseCase.platformClearingId("USD"), 10000L, "USD"),
                new PostingLine(EnsureAccountsExistUseCase.connectedPayableId("USD"), -10000L, "USD"));
        assertThat(cmd.postings()).noneMatch(p -> p.amount() == 0L);
        assertBalanced(cmd.postings());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legs = (List<Map<String, Object>>) cmd.metadata().get("legs");
        assertThat(legs).containsExactly(
                Map.of("connected_account_id", "ca_a", "amount", 10000L),
                Map.of("connected_account_id", "ca_b", "amount", 0L));
    }

    @Test
    void allZeroDistribution_booksNothing_zeroMoneyMovedIsAnHonestNoEntry() {
        adapter.postSplitDistribution(TENANT, SPLIT, PAYMENT, "USD",
                List.of(new LedgerPort.Leg("ca_a", 0L), new LedgerPort.Leg("ca_b", 0L)),
                0L, true);

        verify(createJournalEntry, never()).execute(any());
    }

    @Test
    void lowerCaseCurrency_isNormalised_andTenantStillStamped() {
        adapter.postSplitDistribution(TENANT, SPLIT, PAYMENT, "usd",
                List.of(new LedgerPort.Leg("ca_a", 100L)), 0L, true);

        verify(ensureAccounts).ensureAccountsForCurrency(eq("USD"), eq(TENANT));
        CreateJournalEntryCommand cmd = captureCommand();
        assertThat(cmd.tenantId()).isEqualTo(TENANT);
        assertThat(cmd.postings()).extracting(PostingLine::currency).containsOnly("USD");
        assertThat(cmd.postings()).extracting(PostingLine::ledgerAccountId).containsExactly(
                EnsureAccountsExistUseCase.platformClearingId("USD"),
                EnsureAccountsExistUseCase.connectedPayableId("USD"));
    }
}
