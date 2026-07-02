package io.nexuspay.app;

import io.nexuspay.app.config.TestSecurityConfig;
import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase;
import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase.CreateVendorPaymentCommand;
import io.nexuspay.b2b.application.service.B2bApprovalService;
import io.nexuspay.b2b.domain.VendorPaymentMethod;
import io.nexuspay.b2b.domain.VendorPaymentStatus;
import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.NexusPayPrincipal;
import io.nexuspay.ledger.application.port.JournalEntryRepository;
import io.nexuspay.ledger.domain.JournalEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ★ WAVE1-money-ledger (GAP-068 + GAP-069) end-to-end: threshold-gated maker-checker on vendor
 * payments composed with atomic ledger postings, against real Postgres + the real gateway advice
 * chain. Proves: 202 above threshold; requester/creator self-approval fail-closed; a second
 * principal executes EXACTLY ONCE (payment PAID, accrual + disbursement booked, executed_at set);
 * double-approve and single-step replay never double-book; approvals and entries are tenant-scoped.
 */
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@DisplayName("WAVE1 GATE: b2b maker-checker + ledger, end-to-end")
class B2bMakerCheckerLedgerIT extends IntegrationTestBase {

    private static final String CREATOR = "user-creator";
    private static final String MAKER = "user-maker";
    private static final String REVIEWER = "user-reviewer";

    @Autowired private MockMvc mockMvc;
    @Autowired private ManageVendorPaymentUseCase vendorPayments;
    @Autowired private B2bApprovalService b2bApprovals;
    @Autowired private ApprovalService approvalService;
    @Autowired private JournalEntryRepository journalEntries;

    private String tenant;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(DOCKER_AVAILABLE,
                "Docker unavailable — maker-checker gate self-skips (Testcontainers required)");
        tenant = "mc-" + UUID.randomUUID();
    }

    private Authentication auth(String userId, String tenantId, String role) {
        var principal = new NexusPayPrincipal(userId, tenantId, role, NexusPayPrincipal.AuthMethod.JWT);
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private String createPayment(long amount, String createdBy) {
        return vendorPayments.createVendorPayment(new CreateVendorPaymentCommand(
                tenant, "vendor-1", amount, "USD", VendorPaymentMethod.ACH,
                null, null, createdBy)).paymentId();
    }

    private List<JournalEntry> entriesFor(String paymentId) {
        return journalEntries.findByPaymentReferenceAndTenantId(paymentId, tenant);
    }

    @Test
    @DisplayName("above-threshold approve → 202 pending; self/creator approve 403; second principal executes once")
    void makerChecker_fullLifecycle_overHttp() throws Exception {
        String paymentId = createPayment(60_000, CREATOR); // >= 50000 threshold

        // 1) The MAKER requests approval → 202 + requires_approval + approval id + threshold.
        var mvcResult = mockMvc.perform(post("/v1/vendor-payments/" + paymentId + "/approve")
                        .with(authentication(auth(MAKER, tenant, "admin"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requires_approval").value(true))
                .andExpect(jsonPath("$.approval_threshold").value(50000))
                .andReturn();
        String approvalId = com.jayway.jsonpath.JsonPath.read(
                mvcResult.getResponse().getContentAsString(), "$.approval_id");

        // Nothing money-moving happened yet: PENDING, zero entries.
        assertThat(vendorPayments.getVendorPayment(paymentId, tenant).status())
                .isEqualTo(VendorPaymentStatus.PENDING);
        assertThat(entriesFor(paymentId)).isEmpty();

        // 1b) WAVE1 review fix — IDEMPOTENT RE-REQUEST: an innocent client retry of the 202 must
        //     return the SAME approval id, not mint a duplicate PENDING row (duplicates become
        //     permanently-stuck poison rows once one executes).
        mockMvc.perform(post("/v1/vendor-payments/" + paymentId + "/approve")
                        .with(authentication(auth(MAKER, tenant, "admin"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.approval_id").value(approvalId));
        assertThat(approvalService.listPending(tenant).stream()
                .filter(a -> paymentId.equals(a.getResourceId())))
                .as("exactly one pending approval per resource, no matter how often the maker retries")
                .hasSize(1);

        // 2) The REQUESTER cannot approve their own request (iam requester != reviewer) → 403.
        mockMvc.perform(post("/v1/b2b/approvals/" + approvalId + "/approve")
                        .with(authentication(auth(MAKER, tenant, "admin"))))
                .andExpect(status().isForbidden());

        // 3) The CREATOR (a different principal from the requester) cannot approve either
        //    (fail-closed created_by check) → 403, and the row is still PENDING.
        mockMvc.perform(post("/v1/b2b/approvals/" + approvalId + "/approve")
                        .with(authentication(auth(CREATOR, tenant, "admin"))))
                .andExpect(status().isForbidden());
        assertThat(approvalService.findById(approvalId))
                .hasValueSatisfying(a -> assertThat(a.isPending()).isTrue());

        // 4) Cross-tenant review of the approval id → 404 (no oracle).
        mockMvc.perform(post("/v1/b2b/approvals/" + approvalId + "/approve")
                        .with(authentication(auth(REVIEWER, "other-tenant", "admin"))))
                .andExpect(status().isNotFound());

        // 5) A second, unrelated principal approves → executes: payment PAID, both entries booked.
        mockMvc.perform(post("/v1/b2b/approvals/" + approvalId + "/approve")
                        .with(authentication(auth(REVIEWER, tenant, "admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        assertThat(vendorPayments.getVendorPayment(paymentId, tenant).status())
                .isEqualTo(VendorPaymentStatus.PAID);
        List<JournalEntry> entries = entriesFor(paymentId);
        assertThat(entries).extracting(JournalEntry::getDescription)
                .containsExactlyInAnyOrder("Vendor payment approved", "Vendor payment disbursed");
        assertThat(entries).allSatisfy(e -> assertThat(e.getTenantId()).isEqualTo(tenant));
        // executed_at is stamped (action-agnostic marker) — the row can never be re-driven.
        assertThat(approvalService.findById(approvalId))
                .hasValueSatisfying(a -> {
                    assertThat(a.isApproved()).isTrue();
                    assertThat(a.isExecuted()).isTrue();
                });

        // Tenant isolation of the money data: the same reference under another tenant reads empty.
        assertThat(journalEntries.findByPaymentReferenceAndTenantId(paymentId, "other-tenant")).isEmpty();

        // 6) Double-approve (replay in a NEW transaction): the atomic claim is gone → throws; the
        //    execution ran exactly once and exactly one (id, description) entry pair exists.
        assertThatThrownBy(() -> b2bApprovals.reviewApprove(approvalId, REVIEWER, tenant))
                .isInstanceOf(IllegalStateException.class);
        assertThat(entriesFor(paymentId)).hasSize(2);
    }

    @Test
    @DisplayName("service-level fail-closed guards: self-approve and creator-approve throw before any claim")
    void serviceLevel_failClosedGuards() {
        String paymentId = createPayment(60_000, CREATOR);
        var outcome = vendorPayments.approveVendorPayment(paymentId, tenant, MAKER);
        String approvalId = outcome.pendingApprovalId();

        // requester == reviewer → forbidden (iam), row stays PENDING.
        assertThatThrownBy(() -> b2bApprovals.reviewApprove(approvalId, MAKER, tenant))
                .isInstanceOf(AuthorizationException.class);
        // creator == reviewer → forbidden (b2b fail-closed created_by check), row stays PENDING.
        assertThatThrownBy(() -> b2bApprovals.reviewApprove(approvalId, CREATOR, tenant))
                .isInstanceOf(AuthorizationException.class);
        assertThat(approvalService.findById(approvalId))
                .hasValueSatisfying(a -> assertThat(a.isPending()).isTrue());
        assertThat(entriesFor(paymentId)).isEmpty();

        // cross-tenant review → 404-no-oracle.
        assertThatThrownBy(() -> b2bApprovals.reviewApprove(approvalId, REVIEWER, "other-tenant"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("below-threshold approve stays single-step, books both entries ONCE; replay never re-books")
    void belowThreshold_singleStep_andReplayNeverDoubleBooks() {
        String paymentId = createPayment(10_000, CREATOR); // < 50000 threshold

        var outcome = vendorPayments.approveVendorPayment(paymentId, tenant, MAKER);

        assertThat(outcome.requiresApproval()).isFalse();
        assertThat(outcome.payment().status()).isEqualTo(VendorPaymentStatus.PAID);
        assertThat(entriesFor(paymentId)).hasSize(2);

        // Replay in a NEW transaction: the PENDING-only domain guard throws BEFORE any posting —
        // still exactly one entry per (id, description) pair. (The V4028 unique index remains the
        // concurrency backstop; replays must be domain-stopped so a joined tx is never poisoned.)
        assertThatThrownBy(() -> vendorPayments.approveVendorPayment(paymentId, tenant, MAKER))
                .isInstanceOf(IllegalStateException.class);
        assertThat(entriesFor(paymentId)).hasSize(2);
    }

    @Test
    @DisplayName("vendor payment created over HTTP stamps created_by from the principal (batch too)")
    void createOverHttp_stampsCreatedBy() throws Exception {
        var res = mockMvc.perform(post("/v1/vendor-payments")
                        .with(authentication(auth(CREATOR, tenant, "admin")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendorId\":\"vendor-9\",\"amount\":60000,\"currency\":\"USD\",\"method\":\"ACH\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String paymentId = com.jayway.jsonpath.JsonPath.read(
                res.getResponse().getContentAsString(), "$.paymentId");

        // The stamped creator is enforced at review time: MAKER requests, CREATOR may not approve.
        var outcome = vendorPayments.approveVendorPayment(paymentId, tenant, MAKER);
        assertThatThrownBy(() -> b2bApprovals.reviewApprove(outcome.pendingApprovalId(), CREATOR, tenant))
                .isInstanceOf(AuthorizationException.class);
    }
}
