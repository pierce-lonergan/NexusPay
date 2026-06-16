package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.application.port.out.B2bEventPublisher;
import io.nexuspay.b2b.application.port.out.B2bRepository;
import io.nexuspay.b2b.domain.*;
import io.nexuspay.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link B2bInvoiceService}.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@ExtendWith(MockitoExtension.class)
class B2bInvoiceServiceTest {

    @Mock private B2bRepository repository;
    @Mock private B2bEventPublisher eventPublisher;

    private B2bInvoiceService service;

    @BeforeEach
    void setUp() {
        service = new B2bInvoiceService(repository, eventPublisher);
    }

    @Test
    void createInvoiceFromPO_happyPath() {
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-001", "USD", PaymentTerms.NET_30);
        po.addLineItem(new LineItem("Widget", 10, 100, null, "EA"));
        po.submit();
        po.approve();
        when(repository.findPurchaseOrderById(po.getId(), "tenant-1")).thenReturn(Optional.of(po));
        when(repository.saveInvoice(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.savePurchaseOrder(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createInvoiceFromPO(po.getId(), "tenant-1", "INV-001");

        assertNotNull(result.invoiceId());
        assertTrue(result.invoiceId().startsWith("inv_"));
        assertEquals("INV-001", result.invoiceNumber());
        assertEquals(po.getId(), result.purchaseOrderId());
        assertEquals("buyer-1", result.buyerId());
        assertEquals("seller-1", result.sellerId());
        assertEquals(1000, result.amount());
        assertEquals(InvoiceStatus.DRAFT, result.status());

        verify(repository).saveInvoice(any());
        verify(eventPublisher).publishEvent(eq("B2bInvoice"), any(), eq("InvoiceCreatedFromPO"), any(), eq("tenant-1"));
    }

    @Test
    void createInvoiceFromPO_throwsForDraftPO() {
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-002", "USD", PaymentTerms.NET_30);
        // PO is DRAFT, not APPROVED
        when(repository.findPurchaseOrderById(po.getId(), "tenant-1")).thenReturn(Optional.of(po));

        assertThrows(IllegalStateException.class,
                () -> service.createInvoiceFromPO(po.getId(), "tenant-1", "INV-002"));
    }

    @Test
    void createInvoiceFromPO_foreignTenantPO_throwsNotFoundAndDoesNotReadOrMutate() {
        // SEC-23: tenant-2 caller passes a tenant-1 PO id. The tenant-scoped finder returns
        // empty, so the service must 404 (no oracle) BEFORE reading PO financials or mutating it.
        String foreignPoId = "po_owned_by_tenant_1";
        when(repository.findPurchaseOrderById(foreignPoId, "tenant-2")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.createInvoiceFromPO(foreignPoId, "tenant-2", "INV-EVIL"));

        // No invoice created, no PO mutation, no event published.
        verify(repository, never()).saveInvoice(any());
        verify(repository, never()).savePurchaseOrder(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void sendInvoice_changesStatusToSent() {
        B2bInvoice invoice = B2bInvoice.create("tenant-1", "po_123", "buyer-1", "seller-1",
                "INV-003", 50000, 5000, "USD", PaymentTerms.NET_30, LocalDate.now().plusDays(30));
        when(repository.findInvoiceById(invoice.getId(), "tenant-1")).thenReturn(Optional.of(invoice));
        when(repository.saveInvoice(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.sendInvoice(invoice.getId(), "tenant-1");

        assertEquals(InvoiceStatus.SENT, result.status());
        verify(eventPublisher).publishEvent(eq("B2bInvoice"), any(), eq("InvoiceSent"), any(), eq("tenant-1"));
    }

    @Test
    void markInvoicePaid_changesStatusAndCascadesToPO() {
        B2bInvoice invoice = B2bInvoice.create("tenant-1", "po_123", "buyer-1", "seller-1",
                "INV-004", 50000, 5000, "USD", PaymentTerms.NET_30, LocalDate.now().plusDays(30));
        PurchaseOrder po = PurchaseOrder.create("tenant-1", "buyer-1", "seller-1", "PO-004", "USD", PaymentTerms.NET_30);
        po.setId("po_123");
        po.submit();
        po.approve();
        po.markInvoiced();

        when(repository.findInvoiceById(invoice.getId(), "tenant-1")).thenReturn(Optional.of(invoice));
        when(repository.saveInvoice(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findPurchaseOrderById("po_123", "tenant-1")).thenReturn(Optional.of(po));
        when(repository.savePurchaseOrder(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.markInvoicePaid(invoice.getId(), "tenant-1");

        assertEquals(InvoiceStatus.PAID, result.status());
        assertNotNull(result.paidAt());
        assertEquals(PurchaseOrderStatus.PAID, po.getStatus());
        verify(eventPublisher).publishEvent(eq("B2bInvoice"), any(), eq("InvoicePaid"), any(), eq("tenant-1"));
    }

    @Test
    void getInvoice_throwsWhenNotFound() {
        when(repository.findInvoiceById("inv_missing", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getInvoice("inv_missing", "tenant-1"));
    }
}
