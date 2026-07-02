package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase;
import io.nexuspay.b2b.application.port.out.B2bEventPublisher;
import io.nexuspay.b2b.application.port.out.B2bRepository;
import io.nexuspay.b2b.application.port.out.LedgerPort;
import io.nexuspay.b2b.application.port.out.VendorPaymentExecutionPort;
import io.nexuspay.b2b.config.B2bProperties;
import io.nexuspay.b2b.domain.VendorPayment;
import io.nexuspay.b2b.domain.VendorPaymentMethod;
import io.nexuspay.b2b.domain.VendorPaymentStatus;
import io.nexuspay.common.exception.LedgerException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.PendingApproval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VendorPaymentService} — GAP-068 threshold maker-checker + GAP-069
 * atomic ledger postings (accrual at approval, settlement off the confirmed stub result).
 *
 * @since 0.4.2 (Sprint 4.3), reworked WAVE1-money-ledger
 */
@ExtendWith(MockitoExtension.class)
class VendorPaymentServiceTest {

    private static final long THRESHOLD = 50_000L;

    @Mock private B2bRepository repository;
    @Mock private VendorPaymentExecutionPort executionPort;
    @Mock private B2bEventPublisher eventPublisher;
    @Mock private LedgerPort ledgerPort;
    @Mock private ApprovalService approvalService;

    private VendorPaymentService service;

    @BeforeEach
    void setUp() {
        B2bProperties properties = new B2bProperties(); // default approval-threshold = 50000
        service = new VendorPaymentService(repository, executionPort, eventPublisher,
                ledgerPort, approvalService, properties);
    }

    private static PendingApproval approval(String id, String action, String resourceId,
                                            Map<String, Object> payload) {
        return new PendingApproval(id, action, "VendorPayment", resourceId, payload,
                "PENDING", "maker", null, "tenant-1", Instant.now(), null);
    }

    // ---- create -------------------------------------------------------------------------------

    @Test
    void createVendorPayment_happyPath_stampsCreatedBy() {
        when(repository.saveVendorPayment(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createVendorPayment(new ManageVendorPaymentUseCase.CreateVendorPaymentCommand(
                "tenant-1", "vendor-1", 250000, "USD", VendorPaymentMethod.ACH,
                "INV-001 payment", null, "user-maker"));

        assertNotNull(result.paymentId());
        assertTrue(result.paymentId().startsWith("vp_"));
        assertEquals("vendor-1", result.vendorId());
        assertEquals(250000, result.amount());
        assertEquals(VendorPaymentMethod.ACH, result.method());
        assertEquals(VendorPaymentStatus.PENDING, result.status());
        assertEquals("INV-001 payment", result.remittanceInfo());

        // GAP-068: the creating principal is stamped for the creator != approver review check.
        ArgumentCaptor<VendorPayment> saved = ArgumentCaptor.forClass(VendorPayment.class);
        verify(repository).saveVendorPayment(saved.capture());
        assertEquals("user-maker", saved.getValue().getCreatedBy());

        verify(eventPublisher).publishEvent(eq("VendorPayment"), any(), eq("VendorPaymentCreated"), any(), eq("tenant-1"));
    }

    // ---- approve: below threshold = single-step execute (GAP-069 postings) ---------------------

    @Test
    void approveBelowThreshold_executesSingleStep_booksAccrualAndDisbursement() {
        VendorPayment payment = VendorPayment.create("tenant-1", "vendor-1", 10_000, "USD", VendorPaymentMethod.WIRE);
        when(repository.findVendorPaymentById(payment.getId(), "tenant-1")).thenReturn(Optional.of(payment));
        when(repository.saveVendorPayment(any())).thenAnswer(inv -> inv.getArgument(0));
        when(executionPort.execute(any())).thenReturn(
                new VendorPaymentExecutionPort.ExecutionResult(true, "ref_stub_1", null));

        var outcome = service.approveVendorPayment(payment.getId(), "tenant-1", "user-maker");

        assertFalse(outcome.requiresApproval());
        assertEquals(VendorPaymentStatus.PAID, outcome.payment().status());
        assertEquals("ref_stub_1", outcome.payment().externalReference());

        // No maker-checker row below threshold.
        verifyNoInteractions(approvalService);

        // GAP-069 pipeline order: accrual -> stub execute -> disbursement (off the CONFIRMED
        // result) -> save. Postings precede the persisted state advance in one transaction.
        // livemode=true: CallerMode's fail-closed default off an empty security context.
        InOrder inOrder = inOrder(ledgerPort, executionPort, repository);
        inOrder.verify(ledgerPort).postVendorPaymentApproved("tenant-1", payment.getId(), 10_000L, "USD", true);
        inOrder.verify(executionPort).execute(any());
        inOrder.verify(ledgerPort).postVendorPaymentDisbursed(
                "tenant-1", payment.getId(), "ref_stub_1", 10_000L, "USD", true);
        inOrder.verify(repository).saveVendorPayment(any());

        verify(eventPublisher).publishEvent(eq("VendorPayment"), eq(payment.getId()),
                eq("VendorPaymentApproved"), any(), eq("tenant-1"));
        verify(eventPublisher).publishEvent(eq("VendorPayment"), eq(payment.getId()),
                eq("VendorPaymentPaid"), any(), eq("tenant-1"));
    }

    // ---- approve: at/above threshold = pending approval (GAP-068) ------------------------------

    @Test
    void approveAtOrAboveThreshold_createsPendingApproval_noPostingNoExecution() {
        VendorPayment payment = VendorPayment.create("tenant-1", "vendor-1", THRESHOLD, "USD", VendorPaymentMethod.ACH);
        payment.setCreatedBy("user-creator");
        when(repository.findVendorPaymentById(payment.getId(), "tenant-1")).thenReturn(Optional.of(payment));
        when(approvalService.createApproval(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(approval("appr_1", "vendor_payment_approve", payment.getId(), Map.of()));

        var outcome = service.approveVendorPayment(payment.getId(), "tenant-1", "user-maker");

        assertTrue(outcome.requiresApproval());
        assertEquals("appr_1", outcome.pendingApprovalId());
        // The payment stays PENDING — nothing money-moving happened.
        assertEquals(VendorPaymentStatus.PENDING, outcome.payment().status());
        verifyNoInteractions(ledgerPort);
        verifyNoInteractions(executionPort);
        verify(repository, never()).saveVendorPayment(any());

        // The approval payload carries created_by for the creator != approver fail-closed check.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(approvalService).createApproval(eq("vendor_payment_approve"), eq("VendorPayment"),
                eq(payment.getId()), payloadCaptor.capture(), eq("user-maker"), eq("tenant-1"));
        assertEquals("user-creator", payloadCaptor.getValue().get("created_by"));
        assertEquals(THRESHOLD, payloadCaptor.getValue().get("amount"));

        verify(eventPublisher).publishEvent(eq("VendorPayment"), eq(payment.getId()),
                eq("VendorPaymentApprovalRequested"), any(), eq("tenant-1"));
    }

    @Test
    void approveAtOrAboveThreshold_repeatedRequest_returnsExistingApprovalIdempotently() {
        // WAVE1 review fix: the payment stays PENDING while its approval is pending, so a retried
        // approve call must NOT mint a second pending_approvals row (duplicates become permanently-
        // stuck poison rows once one executes). The existing approval's id is returned in the same
        // 202 shape and no duplicate ApprovalRequested event is emitted.
        VendorPayment payment = VendorPayment.create("tenant-1", "vendor-1", THRESHOLD, "USD", VendorPaymentMethod.ACH);
        when(repository.findVendorPaymentById(payment.getId(), "tenant-1")).thenReturn(Optional.of(payment));
        when(approvalService.findPendingByActionAndResource(
                "vendor_payment_approve", payment.getId(), "tenant-1"))
                .thenReturn(Optional.of(approval("appr_existing", "vendor_payment_approve",
                        payment.getId(), Map.of())));

        var outcome = service.approveVendorPayment(payment.getId(), "tenant-1", "user-maker");

        assertTrue(outcome.requiresApproval());
        assertEquals("appr_existing", outcome.pendingApprovalId());
        verify(approvalService, never()).createApproval(anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(ledgerPort);
    }

    @Test
    void approveVendorPayment_crossTenant_throwsNotFound() {
        // SEC-BATCH-1 (headline write): caller tenant-1 approving a payment owned by tenant-2. The
        // tenant-scoped finder returns empty → 404 → money-moving approval cannot cross tenants.
        when(repository.findVendorPaymentById("vp_foreign", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.approveVendorPayment("vp_foreign", "tenant-1", "user-maker"));
        verify(repository, never()).saveVendorPayment(any());
        verifyNoInteractions(ledgerPort);
    }

    @Test
    void approveVendorPayment_throwsWhenAlreadyApproved_beforeAnyPostingOrApproval() {
        VendorPayment payment = VendorPayment.create("tenant-1", "vendor-1", 100000, "USD", VendorPaymentMethod.ACH);
        payment.approve(); // Already APPROVED
        when(repository.findVendorPaymentById(payment.getId(), "tenant-1")).thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class,
                () -> service.approveVendorPayment(payment.getId(), "tenant-1", "user-maker"));
        verifyNoInteractions(ledgerPort);
        verifyNoInteractions(approvalService);
    }

    // ---- GAP-069 anti-best-effort pins (unit level) ---------------------------------------------

    @Test
    void accrualPostingFailure_propagates_noExecutionNoSave() {
        VendorPayment payment = VendorPayment.create("tenant-1", "vendor-1", 10_000, "USD", VendorPaymentMethod.ACH);
        when(repository.findVendorPaymentById(payment.getId(), "tenant-1")).thenReturn(Optional.of(payment));
        doThrow(LedgerException.accountNotFound("la_vendor_expense_usd"))
                .when(ledgerPort).postVendorPaymentApproved(anyString(), anyString(), anyLong(), anyString(), anyBoolean());

        assertThrows(LedgerException.class,
                () -> service.approveVendorPayment(payment.getId(), "tenant-1", "user-maker"));

        // NOT swallowed: no disbursement was attempted and the state advance was never persisted.
        verifyNoInteractions(executionPort);
        verify(ledgerPort, never()).postVendorPaymentDisbursed(anyString(), anyString(), anyString(), anyLong(), anyString(), anyBoolean());
        verify(repository, never()).saveVendorPayment(any());
    }

    @Test
    void disbursementPostingFailure_propagates_saveNeverReached() {
        VendorPayment payment = VendorPayment.create("tenant-1", "vendor-1", 10_000, "USD", VendorPaymentMethod.ACH);
        when(repository.findVendorPaymentById(payment.getId(), "tenant-1")).thenReturn(Optional.of(payment));
        when(executionPort.execute(any())).thenReturn(
                new VendorPaymentExecutionPort.ExecutionResult(true, "ref_stub_1", null));
        doThrow(LedgerException.accountNotFound("la_cash_clearing_usd"))
                .when(ledgerPort).postVendorPaymentDisbursed(anyString(), anyString(), anyString(), anyLong(), anyString(), anyBoolean());

        assertThrows(LedgerException.class,
                () -> service.approveVendorPayment(payment.getId(), "tenant-1", "user-maker"));

        // The PAID state was never persisted — in the real tx everything (incl. the accrual entry)
        // rolls back together; the LedgerPostingAtomicityIT proves that end-to-end.
        verify(repository, never()).saveVendorPayment(any());
        verify(eventPublisher, never()).publishEvent(any(), any(), eq("VendorPaymentPaid"), any(), any());
    }

    @Test
    void stubExecutionFailure_throws_noDisbursementEntry() {
        VendorPayment payment = VendorPayment.create("tenant-1", "vendor-1", 10_000, "USD", VendorPaymentMethod.ACH);
        when(repository.findVendorPaymentById(payment.getId(), "tenant-1")).thenReturn(Optional.of(payment));
        when(executionPort.execute(any())).thenReturn(
                new VendorPaymentExecutionPort.ExecutionResult(false, null, "rail down"));

        assertThrows(IllegalStateException.class,
                () -> service.approveVendorPayment(payment.getId(), "tenant-1", "user-maker"));

        // The settlement entry keys off the CONFIRMED result — an unconfirmed disbursement books nothing.
        verify(ledgerPort, never()).postVendorPaymentDisbursed(anyString(), anyString(), anyString(), anyLong(), anyString(), anyBoolean());
        verify(repository, never()).saveVendorPayment(any());
    }

    @Test
    void executeApproved_replayOnNonPending_throwsBeforeAnyPosting() {
        // NO-DOUBLE-BOOK unit proof: a replayed execution on an already-PAID payment hits the
        // PENDING-only approve() guard BEFORE any ledger call.
        VendorPayment payment = VendorPayment.create("tenant-1", "vendor-1", 10_000, "USD", VendorPaymentMethod.ACH);
        payment.approve();
        payment.markPaid("ref_prev");
        when(repository.findVendorPaymentById(payment.getId(), "tenant-1")).thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class,
                () -> service.executeApproved(payment.getId(), "tenant-1"));
        verifyNoInteractions(ledgerPort);
        verifyNoInteractions(executionPort);
    }

    // ---- batch / get ----------------------------------------------------------------------------

    @Test
    void createBatch_assignsBatchIdToAllPayments_andStampsCreatedBy() {
        when(repository.saveVendorPayment(any())).thenAnswer(inv -> inv.getArgument(0));

        var commands = List.of(
                new ManageVendorPaymentUseCase.CreateVendorPaymentCommand(
                        "tenant-1", "vendor-1", 50000, "USD", VendorPaymentMethod.ACH, null, null, "user-maker"),
                new ManageVendorPaymentUseCase.CreateVendorPaymentCommand(
                        "tenant-1", "vendor-2", 75000, "USD", VendorPaymentMethod.WIRE, null, null, "user-maker"));

        var results = service.createBatch(commands, "tenant-1");

        assertEquals(2, results.size());
        assertNotNull(results.get(0).batchId());
        assertTrue(results.get(0).batchId().startsWith("batch_"));
        // All payments in same batch
        assertEquals(results.get(0).batchId(), results.get(1).batchId());

        ArgumentCaptor<VendorPayment> saved = ArgumentCaptor.forClass(VendorPayment.class);
        verify(repository, times(2)).saveVendorPayment(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(p -> "user-maker".equals(p.getCreatedBy())));

        verify(eventPublisher).publishEvent(eq("VendorPayment"), any(), eq("VendorPaymentBatchCreated"), any(), eq("tenant-1"));
    }

    @Test
    void getVendorPayment_returnsResult() {
        VendorPayment payment = VendorPayment.create("tenant-1", "vendor-1", 100000, "USD", VendorPaymentMethod.ACH);
        when(repository.findVendorPaymentById(payment.getId(), "tenant-1")).thenReturn(Optional.of(payment));

        var result = service.getVendorPayment(payment.getId(), "tenant-1");

        assertEquals(payment.getId(), result.paymentId());
        assertEquals("vendor-1", result.vendorId());
    }

    @Test
    void getVendorPayment_throwsWhenNotFound() {
        when(repository.findVendorPaymentById("vp_missing", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getVendorPayment("vp_missing", "tenant-1"));
    }

    @Test
    void getVendorPayment_crossTenant_throwsNotFound() {
        // SEC-BATCH-1: payment owned by tenant-2 → empty for tenant-1 → 404.
        when(repository.findVendorPaymentById("vp_foreign", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getVendorPayment("vp_foreign", "tenant-1"));
    }
}
