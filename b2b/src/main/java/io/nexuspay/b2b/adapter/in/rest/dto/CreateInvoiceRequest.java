package io.nexuspay.b2b.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateInvoiceRequest(
        @NotBlank String purchaseOrderId,
        @NotBlank String invoiceNumber
) {}
