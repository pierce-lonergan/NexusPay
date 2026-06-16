package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.application.port.in.ManagePurchaseOrderUseCase;
import io.nexuspay.b2b.application.port.out.B2bEventPublisher;
import io.nexuspay.b2b.application.port.out.B2bRepository;
import io.nexuspay.b2b.domain.*;
import io.nexuspay.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PurchaseOrderService}.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderServiceTest {

    @Mock private B2bRepository repository;
    @Mock private B2bEventPublisher eventPublisher;

    private PurchaseOrderService service;

    @BeforeEach
    void setUp() {
        service = new PurchaseOrderService(repository, eventPublisher);
    }

    @Test
    void createPurchaseOrder_happyPath() {
        when(repository.savePurchaseOrder(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createPurchaseOrder(new ManagePurchaseOrderUseCase.CreatePurchaseOrderCommand(
                "tenant-1", "buyer-1", "seller-1", "PO-001", "USD", PaymentTerms.NET_30, 500,
                List.of(new LineItem("Widget", 10, 100, "WIDGET-01", "EA"))));

        assertNotNull(result.poId());
        assertTrue(result.poId().startsWith("po_"));
        assertEquals("PO-001", result.poNumber());
        assertEquals("buyer-1", result.buyerId());
        assertEquals(1000, result.amount()); // 10 * 100
        assertEquals(500, result.taxAmount());
        assertEquals(PurchaseOrderStatus.DRAFT, result.status());
        assertEquals(PaymentTerms.NET_30, result.terms());

        verify(repository).savePurchaseOrder(any());
        verify(eventPublisher).publishEvent(eq("PurchaseOrder"), any(), eq("PurchaseOrderCreated"), any(), eq("tenant-1"));
    }

    @Test
    void createPurchaseOrder_withMultipleLineItems() {
        when(repository.savePurchaseOrder(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createPurchaseOrder(new ManagePurchaseOrderUseCase.CreatePurchaseOrderCommand(
                "tenant-1", "buyer-1", "seller-1", "PO-002", "USD", PaymentTerms.NET_60, 0,
                List.of(
                        new LineItem("Item A", 5, 200, null, "EA"),
                        new LineItem("Item B", 3, 500, "CODE-B", "BX"))));

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
    void approvePurchaseOrder_changesStatusAndCalculatesDueDate() {
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-005", "USD", PaymentTerms.NET_30);
        po.submit();
        when(repository.findPurchaseOrderById(po.getId(), "tenant-1")).thenReturn(Optional.of(po));
        when(repository.savePurchaseOrder(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.approvePurchaseOrder(po.getId(), "tenant-1");

        assertEquals(PurchaseOrderStatus.APPROVED, result.status());
        assertNotNull(result.dueDate());
        verify(eventPublisher).publishEvent(eq("PurchaseOrder"), any(), eq("PurchaseOrderApproved"), any(), eq("tenant-1"));
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
