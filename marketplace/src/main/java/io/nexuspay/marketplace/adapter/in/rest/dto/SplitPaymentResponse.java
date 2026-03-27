package io.nexuspay.marketplace.adapter.in.rest.dto;

import java.util.List;

/**
 * @since 0.4.1 (Sprint 4.2)
 */
public record SplitPaymentResponse(
        String splitPaymentId,
        String paymentId,
        String status,
        long totalAmount,
        String currency,
        List<SplitRuleResponse> rules,
        long platformFeeAmount
) {
    public record SplitRuleResponse(
            String ruleId,
            String connectedAccountId,
            String splitType,
            long calculatedAmount,
            String currency
    ) {}
}
