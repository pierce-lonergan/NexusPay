package io.nexuspay.b2b.application.service;

import io.nexuspay.b2b.application.port.in.ManageVendorPaymentUseCase;
import io.nexuspay.b2b.application.port.out.B2bEventPublisher;
import io.nexuspay.b2b.application.port.out.B2bRepository;
import io.nexuspay.b2b.application.port.out.VendorPaymentExecutionPort;
import io.nexuspay.b2b.domain.VendorPayment;
import io.nexuspay.b2b.domain.VendorPaymentMethod;
import io.nexuspay.b2b.domain.VendorPaymentStatus;
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
 * Unit tests for {@link VendorPaymentService}.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
@ExtendWith(MockitoExtension.class)
class VendorPaymentServiceTest {

    @Mock private B2bRepository repository;
    @Mock private VendorPaymentExecutionPort executionPort;
    @Mock private B2bEventPublisher eventPublisher;

    private VendorPaymentService service;

    @BeforeEach
    void setUp() {
        service = new VendorPaymentService(repository, executionPort, eventPublisher);
    }

    @Test
    void createVendorPayment_happyPath() {
        when(repository.saveVendorPayment(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createVendorPayment(new ManageVendorPaymentUseCase.CreateVendorPaymentCommand(
                "tenant-1", "vendor-1", 250000, "USD", VendorPaymentMethod.ACH,
                "INV-001 payment", null));

        assertNotNull(result.paymentId());
        assertTrue(result.paymentId().startsWith("vp_"));
        assertEquals("vendor-1", result.vendorId());
        assertEquals(250000, result.amount());
        assertEquals(VendorPaymentMethod.ACH, result.method());
        assertEquals(VendorPaymentStatus.PENDING, result.status());
        assertEquals("INV-001 payment", result.remittanceInfo());

        verify(eventPublisher).publishEvent(eq("VendorPayment"), any(), eq("VendorPaymentCreated"), any(), eq("tenant-1"));
    }

    @Test
    void approveVendorPayment_changesStatusToApproved() {
        VendorPayment payment = VendorPayment.create("tenant-1", "vendor-1", 100000, "USD", VendorPaymentMethod.WIRE);
        // SEC-BATCH-1: payment loaded tenant-scoped before approve().
        when(repository.findVendorPaymentById(payment.getId(), "tenant-1")).thenReturn(Optional.of(payment));
        when(repository.saveVendorPayment(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.approveVendorPayment(payment.getId(), "tenant-1");

        assertEquals(VendorPaymentStatus.APPROVED, result.status());
        verify(eventPublisher).publishEvent(eq("VendorPayment"), any(), eq("VendorPaymentApproved"), any(), eq("tenant-1"));
    }

    @Test
    void approveVendorPayment_crossTenant_throwsNotFound() {
        // SEC-BATCH-1 (headline write): caller tenant-1 approving a payment owned by tenant-2. The
        // tenant-scoped finder returns empty → 404 → money-moving approval cannot cross tenants.
        when(repository.findVendorPaymentById("vp_foreign", "tenant-1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.approveVendorPayment("vp_foreign", "tenant-1"));
        verify(repository, never()).saveVendorPayment(any());
    }

    @Test
    void approveVendorPayment_throwsWhenAlreadyApproved() {
        VendorPayment payment = VendorPayment.create("tenant-1", "vendor-1", 100000, "USD", VendorPaymentMethod.ACH);
        payment.approve(); // Already APPROVED
        when(repository.findVendorPaymentById(payment.getId(), "tenant-1")).thenReturn(Optional.of(payment));

        assertThrows(IllegalStateException.class, () -> service.approveVendorPayment(payment.getId(), "tenant-1"));
    }

    @Test
    void createBatch_assignsBatchIdToAllPayments() {
        when(repository.saveVendorPayment(any())).thenAnswer(inv -> inv.getArgument(0));

        var commands = List.of(
                new ManageVendorPaymentUseCase.CreateVendorPaymentCommand(
                        "tenant-1", "vendor-1", 50000, "USD", VendorPaymentMethod.ACH, null, null),
                new ManageVendorPaymentUseCase.CreateVendorPaymentCommand(
                        "tenant-1", "vendor-2", 75000, "USD", VendorPaymentMethod.WIRE, null, null));

        var results = service.createBatch(commands, "tenant-1");

        assertEquals(2, results.size());
        assertNotNull(results.get(0).batchId());
        assertTrue(results.get(0).batchId().startsWith("batch_"));
        // All payments in same batch
        assertEquals(results.get(0).batchId(), results.get(1).batchId());

        verify(repository, times(2)).saveVendorPayment(any());
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
