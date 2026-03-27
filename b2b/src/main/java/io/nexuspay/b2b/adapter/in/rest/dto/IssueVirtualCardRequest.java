package io.nexuspay.b2b.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;

public record IssueVirtualCardRequest(
        @NotBlank String cardType,
        @Positive long amountLimit,
        @NotBlank String currency,
        Instant expiresAt,
        List<String> merchantCategoryCodes,
        String purchaseOrderId
) {}
