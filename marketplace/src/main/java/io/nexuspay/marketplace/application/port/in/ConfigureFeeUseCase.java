package io.nexuspay.marketplace.application.port.in;

import java.math.BigDecimal;

/**
 * Use case for configuring platform fees on connected accounts.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface ConfigureFeeUseCase {

    FeeConfigResult configureFee(ConfigureFeeCommand command);

    FeeConfigResult getFeeConfig(String accountId, String tenantId);

    record ConfigureFeeCommand(
            String tenantId,
            String connectedAccountId,
            BigDecimal feePercent,
            long feeFixed
    ) {}

    record FeeConfigResult(
            String connectedAccountId,
            BigDecimal feePercent,
            long feeFixed
    ) {}
}
