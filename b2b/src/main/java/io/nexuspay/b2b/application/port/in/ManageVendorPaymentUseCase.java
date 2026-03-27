package io.nexuspay.b2b.application.port.in;

import io.nexuspay.b2b.domain.VendorPaymentMethod;
import io.nexuspay.b2b.domain.VendorPaymentStatus;

import java.time.Instant;
import java.util.List;

/**
 * Use case for creating, approving, and batching vendor payments.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public interface ManageVendorPaymentUseCase {

    VendorPaymentResult createVendorPayment(CreateVendorPaymentCommand command);

    VendorPaymentResult approveVendorPayment(String paymentId, String tenantId);

    List<VendorPaymentResult> createBatch(List<CreateVendorPaymentCommand> commands, String tenantId);

    VendorPaymentResult getVendorPayment(String paymentId, String tenantId);

    record CreateVendorPaymentCommand(
            String tenantId,
            String vendorId,
            long amount,
            String currency,
            VendorPaymentMethod method,
            String remittanceInfo,
            Instant scheduledAt
    ) {}

    record VendorPaymentResult(
            String paymentId,
            String vendorId,
            long amount,
            String currency,
            VendorPaymentMethod method,
            VendorPaymentStatus status,
            String batchId,
            String remittanceInfo,
            String externalReference,
            Instant scheduledAt,
            Instant paidAt,
            Instant createdAt
    ) {}
}
