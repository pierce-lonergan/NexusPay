package io.nexuspay.b2b.adapter.in.rest.dto;

import java.time.Instant;

public record VendorPaymentResponse(
        String paymentId, String vendorId, long amount, String currency,
        String method, String status, String batchId, String remittanceInfo,
        String externalReference, Instant scheduledAt, Instant paidAt, Instant createdAt
) {}
