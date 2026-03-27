package io.nexuspay.b2b.application.port.in;

import io.nexuspay.b2b.domain.InvoiceStatus;
import io.nexuspay.b2b.domain.PaymentTerms;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Use case for creating and managing B2B invoices.
 *
 * @since 0.4.2 (Sprint 4.3)
 */
public interface ManageB2bInvoiceUseCase {

    InvoiceResult createInvoiceFromPO(String purchaseOrderId, String tenantId, String invoiceNumber);

    InvoiceResult getInvoice(String invoiceId, String tenantId);

    InvoiceResult sendInvoice(String invoiceId, String tenantId);

    InvoiceResult markInvoicePaid(String invoiceId, String tenantId);

    record InvoiceResult(
            String invoiceId,
            String purchaseOrderId,
            String invoiceNumber,
            String buyerId,
            String sellerId,
            long amount,
            long taxAmount,
            String currency,
            InvoiceStatus status,
            PaymentTerms terms,
            LocalDate dueDate,
            Instant paidAt,
            Instant createdAt
    ) {}
}
