package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.application.port.in.ManagePurchaseOrderUseCase;
import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase;
import io.nexuspay.b2b.application.port.out.B2bEventPublisher;
import io.nexuspay.b2b.domain.VendorPaymentMethod;
import io.nexuspay.b2b.domain.VendorPaymentStatus;
import io.nexuspay.common.exception.AuthorizationException;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.PendingApproval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * GAP-068 maker-checker unit suite for {@link B2bApprovalService}: fail-closed creator/tenant/action
 * guards BEFORE the claim, iam-enforced requester != reviewer, execute-once under double-approve,
 * and the reject path never executing.
 */
@ExtendWith(MockitoExtension.class)
class B2bApprovalServiceTest {

    private static final String TENANT = "tenant-1";
    private static final String REVIEWER = "user-reviewer";

    @Mock private ApprovalService approvalService;
    @Mock private ManageVendorPaymentUseCase vendorPayments;
    @Mock private ManagePurchaseOrderUseCase purchaseOrders;
    @Mock private B2bEventPublisher eventPublisher;

    private B2bApprovalService service;

    @BeforeEach
    void setUp() {
        service = new B2bApprovalService(approvalService, vendorPayments, purchaseOrders, eventPublisher);
    }

    private static PendingApproval vpApproval(String id, Map<String, Object> payload, String tenant) {
        return new PendingApproval(id, "vendor_payment_approve", "VendorPayment", "vp_1",
                payload, "PENDING", "user-maker", null, tenant, Instant.now(), null);
    }

    private static ManageVendorPaymentUseCase.VendorPaymentResult paidResult() {
        return new ManageVendorPaymentUseCase.VendorPaymentResult(
                "vp_1", "vendor-1", 100_000, "USD", VendorPaymentMethod.ACH,
                VendorPaymentStatus.PAID, null, null, "ref_stub_1", null, Instant.now(), Instant.now());
    }

    private static ManageVendorPaymentUseCase.VendorPaymentResult resultWithStatus(VendorPaymentStatus status) {
        return new ManageVendorPaymentUseCase.VendorPaymentResult(
                "vp_1", "vendor-1", 100_000, "USD", VendorPaymentMethod.ACH,
                status, null, null, null, null, null, Instant.now());
    }

    /** Stubs the WAVE1 stale pre-check: the vendor payment is still in its approvable state. */
    private void stubApprovableVendorPayment() {
        when(vendorPayments.getVendorPayment("vp_1", TENANT))
                .thenReturn(resultWithStatus(VendorPaymentStatus.PENDING));
    }

    // ---- fail-closed guards, all BEFORE any claim ------------------------------------------------

    @Test
    void creatorEqualsReviewer_forbidden_beforeAnyClaim() {
        when(approvalService.findById("appr_1")).thenReturn(Optional.of(
                vpApproval("appr_1", Map.of("payment_id", "vp_1", "created_by", REVIEWER), TENANT)));

        assertThrows(AuthorizationException.class,
                () -> service.reviewApprove("appr_1", REVIEWER, TENANT));

        // FAIL-CLOSED: the claim was never attempted — the row stays PENDING.
        verify(approvalService, never()).approve(anyString(), anyString(), anyString());
        verifyNoInteractions(vendorPayments);
    }

    @Test
    void requesterEqualsReviewer_rejectedByIamApprove() {
        // requester != reviewer lives inside ApprovalService.approve (iam) — always enforced,
        // including for legacy rows without created_by.
        when(approvalService.findById("appr_1")).thenReturn(Optional.of(
                vpApproval("appr_1", Map.of("payment_id", "vp_1"), TENANT)));
        stubApprovableVendorPayment();
        when(approvalService.approve("appr_1", "user-maker", TENANT))
                .thenThrow(AuthorizationException.forbidden("Cannot approve own request"));

        assertThrows(AuthorizationException.class,
                () -> service.reviewApprove("appr_1", "user-maker", TENANT));
        // The stale pre-check reads the resource, but nothing EXECUTES and nothing is marked.
        verify(vendorPayments, never()).executeApproved(anyString(), anyString());
        verify(approvalService, never()).markRefundExecuted(anyString(), anyString());
    }

    @Test
    void crossTenantApprovalId_notFound_noOracle() {
        when(approvalService.findById("appr_foreign")).thenReturn(Optional.of(
                vpApproval("appr_foreign", Map.of(), "OTHER-TENANT")));

        assertThrows(ResourceNotFoundException.class,
                () -> service.reviewApprove("appr_foreign", REVIEWER, TENANT));
        verify(approvalService, never()).approve(anyString(), anyString(), anyString());
    }

    @Test
    void absentApproval_notFound() {
        when(approvalService.findById("appr_missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.reviewApprove("appr_missing", REVIEWER, TENANT));
    }

    @Test
    void nonB2bAction_notFound_cannotHijackRefundApprovals() {
        var refund = new PendingApproval("appr_r", "refund", "Payment", "pay_1",
                Map.of(), "PENDING", "user-maker", null, TENANT, Instant.now(), null);
        when(approvalService.findById("appr_r")).thenReturn(Optional.of(refund));

        assertThrows(ResourceNotFoundException.class,
                () -> service.reviewApprove("appr_r", REVIEWER, TENANT));
        verify(approvalService, never()).approve(anyString(), anyString(), anyString());
    }

    // ---- happy path + execute-once ----------------------------------------------------------------

    @Test
    void happyPath_claimsThenExecutesThenMarksExecuted_inOrder() {
        var approval = vpApproval("appr_1", Map.of("payment_id", "vp_1", "created_by", "user-creator"), TENANT);
        when(approvalService.findById("appr_1")).thenReturn(Optional.of(approval));
        stubApprovableVendorPayment();
        when(approvalService.approve("appr_1", REVIEWER, TENANT)).thenReturn(approval);
        when(vendorPayments.executeApproved("vp_1", TENANT)).thenReturn(paidResult());

        var result = service.reviewApprove("appr_1", REVIEWER, TENANT);

        assertNotNull(result.vendorPayment());
        assertNull(result.purchaseOrder());
        assertEquals(VendorPaymentStatus.PAID, result.vendorPayment().status());

        InOrder inOrder = inOrder(approvalService, vendorPayments);
        inOrder.verify(approvalService).approve("appr_1", REVIEWER, TENANT);
        inOrder.verify(vendorPayments).executeApproved("vp_1", TENANT);
        inOrder.verify(approvalService).markRefundExecuted("appr_1", TENANT);
    }

    @Test
    void purchaseOrderAction_dispatchesToPurchaseOrders() {
        var approval = new PendingApproval("appr_po", "purchase_order_approve", "PurchaseOrder", "po_1",
                Map.of("po_id", "po_1"), "PENDING", "user-maker", null, TENANT, Instant.now(), null);
        when(approvalService.findById("appr_po")).thenReturn(Optional.of(approval));
        // Stale pre-check: the PO is still SUBMITTED (its only approvable state).
        when(purchaseOrders.getPurchaseOrder("po_1", TENANT)).thenReturn(
                new ManagePurchaseOrderUseCase.PurchaseOrderResult(
                        "po_1", "PO-1", "buyer-1", "seller-1", 100_000, 0, "USD",
                        io.nexuspay.b2b.domain.PurchaseOrderStatus.SUBMITTED,
                        io.nexuspay.b2b.domain.PaymentTerms.NET_30, null, java.util.List.of(),
                        Instant.now()));
        when(approvalService.approve("appr_po", REVIEWER, TENANT)).thenReturn(approval);
        when(purchaseOrders.executeApproved("po_1", TENANT)).thenReturn(
                new ManagePurchaseOrderUseCase.PurchaseOrderResult(
                        "po_1", "PO-1", "buyer-1", "seller-1", 100_000, 0, "USD",
                        io.nexuspay.b2b.domain.PurchaseOrderStatus.APPROVED,
                        io.nexuspay.b2b.domain.PaymentTerms.NET_30, null, java.util.List.of(),
                        Instant.now()));

        var result = service.reviewApprove("appr_po", REVIEWER, TENANT);

        assertNull(result.vendorPayment());
        assertNotNull(result.purchaseOrder());
        verify(purchaseOrders).executeApproved("po_1", TENANT);
        verifyNoInteractions(vendorPayments);
        verify(approvalService).markRefundExecuted("appr_po", TENANT);
    }

    @Test
    void doubleApprove_secondClaimLoses_executionInvokedExactlyOnce() {
        var approval = vpApproval("appr_1", Map.of("payment_id", "vp_1"), TENANT);
        when(approvalService.findById("appr_1")).thenReturn(Optional.of(approval));
        stubApprovableVendorPayment();
        // First claim wins; the second loses the atomic PENDING->APPROVED UPDATE (0 rows) — B-009.
        when(approvalService.approve("appr_1", REVIEWER, TENANT))
                .thenReturn(approval)
                .thenThrow(new IllegalStateException("Approval is not pending (already processed): appr_1"));
        when(vendorPayments.executeApproved("vp_1", TENANT)).thenReturn(paidResult());

        service.reviewApprove("appr_1", REVIEWER, TENANT);
        assertThrows(IllegalStateException.class,
                () -> service.reviewApprove("appr_1", REVIEWER, TENANT));

        verify(vendorPayments, times(1)).executeApproved("vp_1", TENANT);
        verify(approvalService, times(1)).markRefundExecuted("appr_1", TENANT);
    }

    // ---- WAVE1 review fix: stale-approval conversion ------------------------------------------------

    @Test
    void stalePendingApproval_resourceNoLongerApprovable_terminallyRejectedAnd409_neverClaims() {
        // A still-PENDING approval whose vendor payment already left PENDING (e.g. executed via a
        // pre-dedup duplicate) can NEVER execute. It must be converted to terminal REJECTED (with
        // the taxonomy rejection event) and 409'd — not claim/fail/rollback to PENDING forever.
        var approval = vpApproval("appr_zombie", Map.of("payment_id", "vp_1"), TENANT);
        when(approvalService.findById("appr_zombie")).thenReturn(Optional.of(approval));
        when(vendorPayments.getVendorPayment("vp_1", TENANT))
                .thenReturn(resultWithStatus(VendorPaymentStatus.PAID));
        when(approvalService.reject("appr_zombie", REVIEWER, TENANT)).thenReturn(approval);

        assertThrows(io.nexuspay.common.exception.ConflictException.class,
                () -> service.reviewApprove("appr_zombie", REVIEWER, TENANT));

        verify(approvalService).reject("appr_zombie", REVIEWER, TENANT);
        verify(approvalService, never()).approve(anyString(), anyString(), anyString());
        verify(vendorPayments, never()).executeApproved(anyString(), anyString());
        verify(eventPublisher).publishEvent(eq("VendorPayment"), eq("vp_1"),
                eq("VendorPaymentApprovalRejected"), any(), eq(TENANT));
    }

    @Test
    void stalePendingApproval_resourceVanished_terminallyRejectedAnd409() {
        var approval = vpApproval("appr_gone", Map.of("payment_id", "vp_1"), TENANT);
        when(approvalService.findById("appr_gone")).thenReturn(Optional.of(approval));
        when(vendorPayments.getVendorPayment("vp_1", TENANT))
                .thenThrow(new ResourceNotFoundException("Vendor payment not found"));
        when(approvalService.reject("appr_gone", REVIEWER, TENANT)).thenReturn(approval);

        assertThrows(io.nexuspay.common.exception.ConflictException.class,
                () -> service.reviewApprove("appr_gone", REVIEWER, TENANT));

        verify(approvalService).reject("appr_gone", REVIEWER, TENANT);
        verify(approvalService, never()).approve(anyString(), anyString(), anyString());
    }

    @Test
    void staleCancelledPurchaseOrder_terminallyRejectedAnd409() {
        var approval = new PendingApproval("appr_po_z", "purchase_order_approve", "PurchaseOrder", "po_1",
                Map.of("po_id", "po_1"), "PENDING", "user-maker", null, TENANT, Instant.now(), null);
        when(approvalService.findById("appr_po_z")).thenReturn(Optional.of(approval));
        when(purchaseOrders.getPurchaseOrder("po_1", TENANT)).thenReturn(
                new ManagePurchaseOrderUseCase.PurchaseOrderResult(
                        "po_1", "PO-1", "buyer-1", "seller-1", 100_000, 0, "USD",
                        io.nexuspay.b2b.domain.PurchaseOrderStatus.CANCELLED,
                        io.nexuspay.b2b.domain.PaymentTerms.NET_30, null, java.util.List.of(),
                        Instant.now()));
        when(approvalService.reject("appr_po_z", REVIEWER, TENANT)).thenReturn(approval);

        assertThrows(io.nexuspay.common.exception.ConflictException.class,
                () -> service.reviewApprove("appr_po_z", REVIEWER, TENANT));

        verify(approvalService).reject("appr_po_z", REVIEWER, TENANT);
        verify(purchaseOrders, never()).executeApproved(anyString(), anyString());
        verify(eventPublisher).publishEvent(eq("PurchaseOrder"), eq("po_1"),
                eq("PurchaseOrderApprovalRejected"), any(), eq(TENANT));
    }

    // ---- reject -----------------------------------------------------------------------------------

    @Test
    void reject_emitsRejectedEvent_neverExecutes() {
        var approval = vpApproval("appr_1", Map.of("payment_id", "vp_1"), TENANT);
        when(approvalService.findById("appr_1")).thenReturn(Optional.of(approval));
        when(approvalService.reject("appr_1", REVIEWER, TENANT)).thenReturn(approval);

        service.reviewReject("appr_1", REVIEWER, TENANT);

        verify(eventPublisher).publishEvent(eq("VendorPayment"), eq("vp_1"),
                eq("VendorPaymentApprovalRejected"), any(), eq(TENANT));
        verifyNoInteractions(vendorPayments);
        verifyNoInteractions(purchaseOrders);
        verify(approvalService, never()).markRefundExecuted(anyString(), anyString());
    }

    @Test
    void reject_crossTenant_notFound() {
        when(approvalService.findById("appr_foreign")).thenReturn(Optional.of(
                vpApproval("appr_foreign", Map.of(), "OTHER-TENANT")));

        assertThrows(ResourceNotFoundException.class,
                () -> service.reviewReject("appr_foreign", REVIEWER, TENANT));
        verify(approvalService, never()).reject(anyString(), anyString(), anyString());
    }
}
