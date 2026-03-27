package io.nexuspay.b2b.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public record CreateVendorPaymentRequest(
        @NotBlank String vendorId,
        @Positive long amount,
        @NotBlank String currency,
        @NotBlank String method,
        String remittanceInfo,
        Instant scheduledAt
) {}
