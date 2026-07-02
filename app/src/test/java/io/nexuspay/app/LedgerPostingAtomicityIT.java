package io.nexuspay.app;

import io.nexuspay.app.config.FaultInjectableCreateJournalEntryUseCase;
import io.nexuspay.app.config.TestSecurityConfig;
import io.nexuspay.b2b.application.port.in.ManageB2bInvoiceUseCase;
import io.nexuspay.b2b.application.port.in.ManagePurchaseOrderUseCase;
import io.nexuspay.b2b.application.port.in.ManagePurchaseOrderUseCase.CreatePurchaseOrderCommand;
import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase;
import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase.CreateVendorPaymentCommand;
import io.nexuspay.b2b.application.service.B2bApprovalService;
import io.nexuspay.b2b.domain.InvoiceStatus;
import io.nexuspay.b2b.domain.LineItem;
import io.nexuspay.b2b.domain.PaymentTerms;
import io.nexuspay.b2b.domain.VendorPaymentMethod;
import io.nexuspay.b2b.domain.VendorPaymentStatus;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.ledger.application.EnsureAccountsExistUseCase;
import io.nexuspay.ledger.application.port.JournalEntryRepository;
import io.nexuspay.ledger.domain.JournalEntry;
import io.nexuspay.ledger.domain.Posting;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase.CreateSplitCommand;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase.SplitRuleCommand;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.domain.ConnectedAccount;
import io.nexuspay.marketplace.domain.KycStatus;
import io.nexuspay.marketplace.domain.SplitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ WAVE1-money-ledger: THE anti-best-effort proof (GAP-063 / GAP-069 CARDINAL RULE).
 *
 * <p>The ledger is the MONEY TRUTH: a journal posting must be ATOMIC with its money-state
 * transition. With the {@link FaultInjectableCreateJournalEntryUseCase} armed to throw, every new
 * posting site must FAIL its transition — the split/invoice/payment state must NOT advance and
 * zero journal entries may survive. Disarmed, every transition books per-currency zero-sum
 * entries. This is the exact opposite contract of the GAP-076 best-effort projection, proven
 * against real Postgres transactions (Testcontainers), not mocks.</p>
 */
@Import(TestSecurityConfig.class)
@DisplayName("WAVE1 GATE: ledger postings are atomic with their money-state transitions")
class LedgerPostingAtomicityIT extends IntegrationTestBase {

    @Autowired private CreateSplitPaymentUseCase splitPayments;
    @Autowired private MarketplaceRepository marketplaceRepository;
    @Autowired private ManageVendorPaymentUseCase vendorPayments;
    @Autowired private ManagePurchaseOrderUseCase purchaseOrders;
    @Autowired private ManageB2bInvoiceUseCase invoices;
    @Autowired private B2bApprovalService b2bApprovals;
    @Autowired private ApprovalService approvalService;
    @Autowired private JournalEntryRepository journalEntries;
    @Autowired private io.nexuspay.ledger.application.port.LedgerAccountRepository ledgerAccounts;

    private String tenant;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — atomicity gate self-skips (Testcontainers required)");
        tenant = "wave1-" + UUID.randomUUID();
        FaultInjectableCreateJournalEntryUseCase.clearFault();
    }

    // ------------------------------------------------------------------ helpers

    private String seedActiveAccount() {
        ConnectedAccount account = ConnectedAccount.create(
                tenant, "Wave1 LLC", "wave1-" + UUID.randomUUID() + "@example.com", "US", "USD");
        account.updateKycStatus(KycStatus.VERIFIED);
        account.activate();
        return marketplaceRepository.saveAccount(account).getId();
    }

    /** Creates a SUBMITTED sub-threshold PO, approves it, and returns a SENT invoice id from it. */
    private String seedSentInvoice() {
        var po = purchaseOrders.createPurchaseOrder(new CreatePurchaseOrderCommand(
                tenant, "buyer-1", "seller-1", "PO-" + UUID.randomUUID(), "USD",
                PaymentTerms.NET_30, 0,
                List.of(new LineItem("Widget", 10, 100, null, "EA")), "user-creator"));
        purchaseOrders.submitPurchaseOrder(po.poId(), tenant);
        purchaseOrders.approvePurchaseOrder(po.poId(), tenant, "user-maker"); // 1000 < threshold
        var invoice = invoices.createInvoiceFromPO(po.poId(), tenant, "INV-" + UUID.randomUUID());
        invoices.sendInvoice(invoice.invoiceId(), tenant);
        return invoice.invoiceId();
    }

    private String createVendorPayment(long amount) {
        return vendorPayments.createVendorPayment(new CreateVendorPaymentCommand(
                tenant, "vendor-1", amount, "USD", VendorPaymentMethod.ACH,
                null, null, "user-creator")).paymentId();
    }

    private void assertPerCurrencyZeroSum(JournalEntry entry) {
        Map<String, Long> byCurrency = entry.getPostings().stream()
                .collect(Collectors.groupingBy(Posting::currency,
                        Collectors.summingLong(Posting::amount)));
        assertThat(byCurrency.values())
                .as("per-currency zero-sum (L-001) for entry %s / %s", entry.getId(), entry.getDescription())
                .allMatch(sum -> sum == 0L);
    }

    // ------------------------------------------------------------------ ARMED: rollback proofs

    @Test
    @DisplayName("split creation ROLLS BACK when the ledger posting fails — no split row, no entries")
    void splitCreation_rollsBack_whenPostingFails() {
        String account = seedActiveAccount();
        String paymentId = "pi_atom_" + UUID.randomUUID();

        FaultInjectableCreateJournalEntryUseCase.armFault(
                new RuntimeException("armed ledger fault (split)"));
        try {
            assertThatThrownBy(() -> splitPayments.createSplitPayment(new CreateSplitCommand(
                    tenant, paymentId, 10_000, "USD",
                    List.of(new SplitRuleCommand(account, SplitType.REMAINDER, 0, null)))))
                    .hasMessageContaining("armed ledger fault (split)");
        } finally {
            FaultInjectableCreateJournalEntryUseCase.clearFault();
        }

        // The transition did NOT advance: no split row survived the rollback ...
        assertThat(marketplaceRepository.findSplitPaymentByTenantAndPaymentId(tenant, paymentId))
                .as("split row must roll back with the failed posting")
                .isEmpty();
    }

    @Test
    @DisplayName("markInvoicePaid ROLLS BACK when the ledger posting fails — invoice stays SENT")
    void invoiceMarkPaid_rollsBack_whenPostingFails() {
        String invoiceId = seedSentInvoice();

        FaultInjectableCreateJournalEntryUseCase.armFault(
                new RuntimeException("armed ledger fault (invoice)"));
        try {
            assertThatThrownBy(() -> invoices.markInvoicePaid(invoiceId, tenant))
                    .hasMessageContaining("armed ledger fault (invoice)");
        } finally {
            FaultInjectableCreateJournalEntryUseCase.clearFault();
        }

        assertThat(invoices.getInvoice(invoiceId, tenant).status())
                .as("invoice must still be SENT after the rolled-back markPaid")
                .isEqualTo(InvoiceStatus.SENT);
        assertThat(journalEntries.findByPaymentReferenceAndTenantId(invoiceId, tenant))
                .as("zero journal entries for the rolled-back invoice payment")
                .isEmpty();
    }

    @Test
    @DisplayName("below-threshold vendor approve ROLLS BACK when a posting fails — payment stays PENDING")
    void vendorApprove_belowThreshold_rollsBack_whenPostingFails() {
        String paymentId = createVendorPayment(10_000); // < 50000 threshold

        FaultInjectableCreateJournalEntryUseCase.armFault(
                new RuntimeException("armed ledger fault (vendor)"));
        try {
            assertThatThrownBy(() -> vendorPayments.approveVendorPayment(paymentId, tenant, "user-maker"))
                    .hasMessageContaining("armed ledger fault (vendor)");
        } finally {
            FaultInjectableCreateJournalEntryUseCase.clearFault();
        }

        assertThat(vendorPayments.getVendorPayment(paymentId, tenant).status())
                .as("payment must still be PENDING after the rolled-back approve")
                .isEqualTo(VendorPaymentStatus.PENDING);
        assertThat(journalEntries.findByPaymentReferenceAndTenantId(paymentId, tenant))
                .as("zero journal entries for the rolled-back vendor approval")
                .isEmpty();
    }

    @Test
    @DisplayName("maker-checker review ROLLS BACK when a posting fails — approval returns to PENDING")
    void makerCheckerReview_rollsBack_whenPostingFails_approvalBackToPending() {
        String paymentId = createVendorPayment(60_000); // >= 50000 threshold
        var outcome = vendorPayments.approveVendorPayment(paymentId, tenant, "user-maker");
        assertThat(outcome.requiresApproval()).isTrue();
        String approvalId = outcome.pendingApprovalId();

        FaultInjectableCreateJournalEntryUseCase.armFault(
                new RuntimeException("armed ledger fault (review)"));
        try {
            assertThatThrownBy(() -> b2bApprovals.reviewApprove(approvalId, "user-reviewer", tenant))
                    .hasMessageContaining("armed ledger fault (review)");
        } finally {
            FaultInjectableCreateJournalEntryUseCase.clearFault();
        }

        // The atomic claim rolled back with the failed execution: the row is PENDING again (no
        // B-022 stuck-APPROVED class on this in-process path), the payment never advanced, and
        // there is zero ledger residue.
        assertThat(approvalService.findById(approvalId))
                .hasValueSatisfying(a -> assertThat(a.isPending()).isTrue());
        assertThat(vendorPayments.getVendorPayment(paymentId, tenant).status())
                .isEqualTo(VendorPaymentStatus.PENDING);
        assertThat(journalEntries.findByPaymentReferenceAndTenantId(paymentId, tenant)).isEmpty();
    }

    // ------------------------------------------------------------------ DISARMED: balance proofs

    @Test
    @DisplayName("disarmed: split creation books ONE balanced entry (DR clearing == legs + fee)")
    void splitCreation_disarmed_booksBalancedEntry() {
        String accountA = seedActiveAccount();
        String accountB = seedActiveAccount();
        String paymentId = "pi_bal_" + UUID.randomUUID();

        var result = splitPayments.createSplitPayment(new CreateSplitCommand(
                tenant, paymentId, 10_000, "USD",
                List.of(
                        new SplitRuleCommand(accountA, SplitType.PERCENTAGE, 0, new java.math.BigDecimal("80")),
                        new SplitRuleCommand(accountB, SplitType.REMAINDER, 0, null))));

        List<JournalEntry> entries =
                journalEntries.findByPaymentReferenceAndTenantId(result.splitPaymentId(), tenant);
        assertThat(entries).hasSize(1);
        JournalEntry entry = entries.get(0);
        assertThat(entry.getDescription()).isEqualTo("Split payment created");
        assertThat(entry.getTenantId()).isEqualTo(tenant);
        assertPerCurrencyZeroSum(entry);

        // DR platform clearing == sum of all leg credits (+ fee 0 here) — across ALL legs.
        long clearingDebit = entry.getPostings().stream()
                .filter(p -> p.ledgerAccountId().equals(EnsureAccountsExistUseCase.platformClearingId("USD")))
                .mapToLong(Posting::amount).sum();
        long legCredits = entry.getPostings().stream()
                .filter(p -> p.ledgerAccountId().equals(EnsureAccountsExistUseCase.connectedPayableId("USD")))
                .mapToLong(Posting::amount).sum();
        assertThat(clearingDebit).isEqualTo(10_000L);
        assertThat(clearingDebit + legCredits).isZero();

        // WAVE1 BLOCKER fix (cross-tenant balance exposure): the platform-shared per-currency
        // singleton accounts this posting used must NEVER surface in the transacting tenant's
        // balance read (GET /v1/ledger/accounts -> findAllByTenantId). They are stamped
        // DEFAULT_TENANT, so a tenant can never read the cross-tenant aggregate posted_balance —
        // and by symmetry tenant A's balance view can never reflect tenant B's postings.
        var sharedIds = List.of(
                EnsureAccountsExistUseCase.platformClearingId("USD"),
                EnsureAccountsExistUseCase.connectedPayableId("USD"),
                EnsureAccountsExistUseCase.platformFeeRevenueId("USD"),
                EnsureAccountsExistUseCase.accountsPayableId("USD"),
                EnsureAccountsExistUseCase.cashClearingId("USD"),
                EnsureAccountsExistUseCase.vendorPayableId("USD"),
                EnsureAccountsExistUseCase.vendorExpenseId("USD"));
        assertThat(ledgerAccounts.findAllByTenantId(tenant))
                .as("no platform-shared ledger account may be attributed to the transacting tenant")
                .noneMatch(a -> sharedIds.contains(a.getId()));
        sharedIds.forEach(id -> ledgerAccounts.findById(id).ifPresent(a ->
                assertThat(a.getTenantId())
                        .as("platform-shared account %s must stay on the invisible DEFAULT tenant", id)
                        .isEqualTo(EnsureAccountsExistUseCase.DEFAULT_TENANT)));
    }

    @Test
    @DisplayName("disarmed: [PERCENTAGE 100, REMAINDER] — a 0-amount leg still creates the split; no 0-amount posting")
    void splitCreation_zeroAmountRemainderLeg_stillCreates_andBalances() {
        // WAVE1 review fix: a REMAINDER leg legitimately resolves to 0 when PERCENTAGE 100 consumes
        // the whole distributable amount (valid per validateSplitRules, creatable before this wave).
        // Posting rejects amount == 0, so the adapter must skip the 0-leg's line (keeping its
        // identity in metadata) instead of 500-ing the create.
        String accountA = seedActiveAccount();
        String accountB = seedActiveAccount();
        String paymentId = "pi_zero_" + UUID.randomUUID();

        var result = splitPayments.createSplitPayment(new CreateSplitCommand(
                tenant, paymentId, 10_000, "USD",
                List.of(
                        new SplitRuleCommand(accountA, SplitType.PERCENTAGE, 0, new java.math.BigDecimal("100")),
                        new SplitRuleCommand(accountB, SplitType.REMAINDER, 0, null))));

        List<JournalEntry> entries =
                journalEntries.findByPaymentReferenceAndTenantId(result.splitPaymentId(), tenant);
        assertThat(entries).hasSize(1);
        JournalEntry entry = entries.get(0);
        assertPerCurrencyZeroSum(entry);
        assertThat(entry.getPostings()).noneMatch(p -> p.amount() == 0L);
        // Exactly one leg credit (the 100% leg) + the composed DR.
        assertThat(entry.getPostings()).hasSize(2);
    }

    @Test
    @DisplayName("disarmed: markInvoicePaid books DR accounts payable / CR cash clearing for amount+tax")
    void invoiceMarkPaid_disarmed_booksBalancedEntry() {
        String invoiceId = seedSentInvoice();

        invoices.markInvoicePaid(invoiceId, tenant);

        List<JournalEntry> entries = journalEntries.findByPaymentReferenceAndTenantId(invoiceId, tenant);
        assertThat(entries).hasSize(1);
        JournalEntry entry = entries.get(0);
        assertThat(entry.getDescription()).isEqualTo("B2B invoice paid");
        assertPerCurrencyZeroSum(entry);
        assertThat(entry.getPostings())
                .extracting(Posting::ledgerAccountId)
                .containsExactlyInAnyOrder(
                        EnsureAccountsExistUseCase.accountsPayableId("USD"),
                        EnsureAccountsExistUseCase.cashClearingId("USD"));
    }

    @Test
    @DisplayName("disarmed: vendor approve books accrual + disbursement, both balanced, payment PAID")
    void vendorApprove_disarmed_booksAccrualAndDisbursement() {
        String paymentId = createVendorPayment(10_000);

        var outcome = vendorPayments.approveVendorPayment(paymentId, tenant, "user-maker");

        assertThat(outcome.requiresApproval()).isFalse();
        assertThat(outcome.payment().status()).isEqualTo(VendorPaymentStatus.PAID);
        assertThat(outcome.payment().externalReference()).isNotBlank();

        List<JournalEntry> entries = journalEntries.findByPaymentReferenceAndTenantId(paymentId, tenant);
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(JournalEntry::getDescription)
                .containsExactlyInAnyOrder("Vendor payment approved", "Vendor payment disbursed");
        entries.forEach(this::assertPerCurrencyZeroSum);
    }
}
