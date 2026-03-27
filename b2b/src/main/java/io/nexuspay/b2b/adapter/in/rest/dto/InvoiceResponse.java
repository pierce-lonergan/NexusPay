package io.nexuspay.b2b.adapter.in.rest.dto;

import java.time.Instant;
import java.time.LocalDate;

public record InvoiceResponse(
        String invoiceId, String purchaseOrderId, String invoiceNumber,
        String buyerId, String sellerId, long amount, long taxAmount,
        String currency, String status, String terms, LocalDate dueDate,
        Instant paidAt, Instant createdAt
) {}
