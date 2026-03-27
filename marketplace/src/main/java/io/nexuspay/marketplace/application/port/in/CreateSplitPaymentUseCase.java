package io.nexuspay.marketplace.application.port.in;

import io.nexuspay.marketplace.domain.SplitPaymentStatus;
import io.nexuspay.marketplace.domain.SplitType;

import java.math.BigDecimal;
import java.util.List;

/**
 * Use case for creating and managing split payments across connected accounts.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface CreateSplitPaymentUseCase {

    SplitPaymentResult createSplitPayment(CreateSplitCommand command);

    SplitPaymentResult getSplitPayment(String splitPaymentId, String tenantId);

    record CreateSplitCommand(
            String tenantId,
            String paymentId,
            long totalAmount,
            String currency,
            List<SplitRuleCommand> rules
    ) {}

    record SplitRuleCommand(
            String connectedAccountId,
            SplitType splitType,
            long amount,
            BigDecimal percentage
    ) {}

    record SplitPaymentResult(
            String splitPaymentId,
            String paymentId,
            SplitPaymentStatus status,
            long totalAmount,
            String currency,
            List<SplitRuleResult> rules,
            long platformFeeAmount
    ) {}

    record SplitRuleResult(
            String ruleId,
            String connectedAccountId,
            SplitType splitType,
            long calculatedAmount,
            String currency
    ) {}
}
