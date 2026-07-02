package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.application.port.in.ManagePurchaseOrderUseCase;
import io.nexuspay.b2b.application.port.out.B2bEventPublisher;
import io.nexuspay.b2b.application.port.out.B2bRepository;
import io.nexuspay.b2b.config.B2bProperties;
import io.nexuspay.b2b.domain.*;
import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.iam.application.ApprovalService;
import io.nexuspay.iam.domain.PendingApproval;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PurchaseOrderService} — GAP-068 threshold maker-checker. GAP-069
 * PO-commitment decision pin: this service deliberately has NO ledger dependency at all (an
 * approved PO is an executory commitment, not a money movement), so "posts NOTHING to the
 * ledger" is enforced structurally — there is no LedgerPort to call.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceTest {

    @Mock private B2bRepository repository;
    @Mock private B2bEventPublisher eventPublisher;
    @Mock private ApprovalService approvalService;

    private PurchaseOrderService service;

    @BeforeEach
    void setUp() {
        B2bProperties properties = new B2bProperties(); // default approval-threshold = 50000
        service = new PurchaseOrderService(repository, eventPublisher, approvalService, properties);
    }

    @Test
    void createPurchaseOrder_happyPath() {
        when(repository.savePurchaseOrder(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createPurchaseOrder(new ManagePurchaseOrderUseCase.CreatePurchaseOrderCommand(
                "tenant-1", "buyer-1", "seller-1", "PO-001", "USD", PaymentTerms.NET_30, 500,
                List.of(new LineItem("Widget", 10, 100, "WIDGET-01", "EA")), "user-maker"));

        assertNotNull(result.poId());
        assertTrue(result.poId().startsWith("po_"));
        assertEquals("PO-001", result.poNumber());
        assertEquals("buyer-1", result.buyerId());
        assertEquals(1000, result.amount()); // 10 * 100
        assertEquals(500, result.taxAmount());
        assertEquals(PurchaseOrderStatus.DRAFT, result.status());
        assertEquals(PaymentTerms.NET_30, result.terms());

        // GAP-068: the creating principal is stamped for the creator != approver review check.
        ArgumentCaptor<PurchaseOrder> saved = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(repository).savePurchaseOrder(saved.capture());
        assertEquals("user-maker", saved.getValue().getCreatedBy());
        verify(eventPublisher).publishEvent(eq("PurchaseOrder"), any(), eq("PurchaseOrderCreated"), any(), eq("tenant-1"));
    }

    @Test
    void createPurchaseOrder_withMultipleLineItems() {
        when(repository.savePurchaseOrder(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createPurchaseOrder(new ManagePurchaseOrderUseCase.CreatePurchaseOrderCommand(
                "tenant-1", "buyer-1", "seller-1", "PO-002", "USD", PaymentTerms.NET_60, 0,
                List.of(
                        new LineItem("Item A", 5, 200, null, "EA"),
                        new LineItem("Item B", 3, 500, "CODE-B", "BX")), null));

        assertEquals(2500, result.amount()); // (5*200) + (3*500)
        assertEquals(2, result.lineItems().size());
    }

    @Test
    void getPurchaseOrder_returnsResult() {
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-003", "USD", PaymentTerms.NET_30);
        when(repository.findPurchaseOrderById(po.getId(), "tenant-1")).thenReturn(Optional.of(po));

        var result = service.getPurchaseOrder(po.getId(), "tenant-1");

        assertEquals(po.getId(), result.poId());
        assertEquals("PO-003", result.poNumber());
    }

    @Test
    void getPurchaseOrder_throwsWhenNotFound() {
        when(repository.findPurchaseOrderById("po_missing", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getPurchaseOrder("po_missing", "tenant-1"));
    }

    @Test
    void submitPurchaseOrder_changesStatusToSubmitted() {
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-004", "USD", PaymentTerms.NET_30);
        when(repository.findPurchaseOrderById(po.getId(), "tenant-1")).thenReturn(Optional.of(po));
        when(repository.savePurchaseOrder(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.submitPurchaseOrder(po.getId(), "tenant-1");

        assertEquals(PurchaseOrderStatus.SUBMITTED, result.status());
        verify(eventPublisher).publishEvent(eq("PurchaseOrder"), any(), eq("PurchaseOrderSubmitted"), any(), eq("tenant-1"));
    }

    @Test
    void approvePurchaseOrder_belowThreshold_singleStep_changesStatusAndCalculatesDueDate() {
        // GAP-068: total (0 + 0) < threshold 50000 → the current single-step approve stands.
        // GAP-069: nothing ledger-shaped exists in this service to call — the PO-commitment pin.
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-005", "USD", PaymentTerms.NET_30);
        po.submit();
        when(repository.findPurchaseOrderById(po.getId(), "tenant-1")).thenReturn(Optional.of(po));
        when(repository.savePurchaseOrder(any())).thenAnswer(inv -> inv.getArgument(0));

        var outcome = service.approvePurchaseOrder(po.getId(), "tenant-1", "user-maker");

        assertFalse(outcome.requiresApproval());
        assertEquals(PurchaseOrderStatus.APPROVED, outcome.purchaseOrder().status());
        assertNotNull(outcome.purchaseOrder().dueDate());
        verifyNoInteractions(approvalService);
        verify(eventPublisher).publishEvent(eq("PurchaseOrder"), any(), eq("PurchaseOrderApproved"), any(), eq("tenant-1"));
    }

    @Test
    void approvePurchaseOrder_atOrAboveThreshold_returnsPending_createsApproval_poStaysSubmitted() {
        // amount 100 * 1000 = 100000 >= threshold 50000 (compared against amount + taxAmount).
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-BIG", "USD", PaymentTerms.NET_30);
        po.addLineItem(new LineItem("Server", 100, 1000, null, "EA"));
        po.setCreatedBy("user-creator");
        po.submit();
        when(repository.findPurchaseOrderById(po.getId(), "tenant-1")).thenReturn(Optional.of(po));
        when(approvalService.createApproval(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(new PendingApproval("appr_po_1", "purchase_order_approve", "PurchaseOrder",
                        po.getId(), Map.of(), "PENDING", "user-maker", null, "tenant-1", Instant.now(), null));

        var outcome = service.approvePurchaseOrder(po.getId(), "tenant-1", "user-maker");

        assertTrue(outcome.requiresApproval());
        assertEquals("appr_po_1", outcome.pendingApprovalId());
        assertEquals(PurchaseOrderStatus.SUBMITTED, outcome.purchaseOrder().status()); // NOT approved
        verify(repository, never()).savePurchaseOrder(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(approvalService).createApproval(eq("purchase_order_approve"), eq("PurchaseOrder"),
                eq(po.getId()), payloadCaptor.capture(), eq("user-maker"), eq("tenant-1"));
        assertEquals("user-creator", payloadCaptor.getValue().get("created_by"));
        assertEquals(100_000L, payloadCaptor.getValue().get("amount"));

        verify(eventPublisher).publishEvent(eq("PurchaseOrder"), eq(po.getId()),
                eq("PurchaseOrderApprovalRequested"), any(), eq("tenant-1"));
        verify(eventPublisher, never()).publishEvent(any(), any(), eq("PurchaseOrderApproved"), any(), any());
    }

    @Test
    void approvePurchaseOrder_repeatedRequest_returnsExistingApprovalIdempotently() {
        // WAVE1 review fix: the PO stays SUBMITTED while its approval is pending, so a retried
        // approve call must return the EXISTING approval's id instead of stacking a duplicate
        // PENDING row (duplicates become permanently-stuck poison rows).
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-DUP", "USD", PaymentTerms.NET_30);
        po.addLineItem(new LineItem("Server", 100, 1000, null, "EA"));
        po.submit();
        when(repository.findPurchaseOrderById(po.getId(), "tenant-1")).thenReturn(Optional.of(po));
        when(approvalService.findPendingByActionAndResource("purchase_order_approve", po.getId(), "tenant-1"))
                .thenReturn(Optional.of(new PendingApproval("appr_existing", "purchase_order_approve",
                        "PurchaseOrder", po.getId(), Map.of(), "PENDING", "user-maker", null,
                        "tenant-1", Instant.now(), null)));

        var outcome = service.approvePurchaseOrder(po.getId(), "tenant-1", "user-maker");

        assertTrue(outcome.requiresApproval());
        assertEquals("appr_existing", outcome.pendingApprovalId());
        assertEquals(PurchaseOrderStatus.SUBMITTED, outcome.purchaseOrder().status());
        verify(approvalService, never()).createApproval(anyString(), anyString(), anyString(), any(), anyString(), anyString());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void cancelPurchaseOrder_changesStatusToCancelled() {
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-006", "USD", PaymentTerms.NET_30);
        when(repository.findPurchaseOrderById(po.getId(), "tenant-1")).thenReturn(Optional.of(po));
        when(repository.savePurchaseOrder(any())).thenAnswer(inv -> inv.getArgument(0));

        service.cancelPurchaseOrder(po.getId(), "tenant-1");

        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(repository).savePurchaseOrder(captor.capture());
        assertEquals(PurchaseOrderStatus.CANCELLED, captor.getValue().getStatus());
        verify(eventPublisher).publishEvent(eq("PurchaseOrder"), any(), eq("PurchaseOrderCancelled"), any(), eq("tenant-1"));
    }

    @Test
    void submitPurchaseOrder_throwsWhenNotDraft() {
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-007", "USD", PaymentTerms.NET_30);
        po.submit(); // Now SUBMITTED
        when(repository.findPurchaseOrderById(po.getId(), "tenant-1")).thenReturn(Optional.of(po));

        assertThrows(IllegalStateException.class, () -> service.submitPurchaseOrder(po.getId(), "tenant-1"));
    }
}
