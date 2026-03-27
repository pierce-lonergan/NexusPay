package io.nexuspay.marketplace.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;

/**
 * @since 0.4.1 (Sprint 4.2)
 */
public record CreateSplitPaymentRequest(
        @NotBlank String paymentId,
        @Positive long totalAmount,
        @NotBlank String currency,
        @NotEmpty List<SplitRuleRequest> rules
) {
    public record SplitRuleRequest(
            @NotBlank String connectedAccountId,
            @NotBlank String splitType,
            long amount,
            BigDecimal percentage
    ) {}
}
