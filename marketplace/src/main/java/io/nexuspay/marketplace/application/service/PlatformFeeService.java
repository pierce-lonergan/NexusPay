package io.nexuspay.marketplace.application.service;

import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.marketplace.application.port.in.ConfigureFeeUseCase;
import io.nexuspay.marketplace.application.port.out.MarketplaceEventPublisher;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.domain.ConnectedAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Service for configuring platform fee rates on connected accounts.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Service
public class PlatformFeeService implements ConfigureFeeUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlatformFeeService.class);

    private final MarketplaceRepository repository;
    private final MarketplaceEventPublisher eventPublisher;

    public PlatformFeeService(MarketplaceRepository repository,
                               MarketplaceEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public FeeConfigResult configureFee(ConfigureFeeCommand command) {
        // SEC-BATCH-1: tenant-scoped write — a tenant can only configure fees on its own account.
        ConnectedAccount account = TenantOwnership.require(
                repository.findAccountById(command.connectedAccountId(), command.tenantId()),
                "Connected account");

        account.setPlatformFeePercent(command.feePercent());
        account.setPlatformFeeFixed(command.feeFixed());
        repository.saveAccount(account);

        eventPublisher.publishEvent("ConnectedAccount", account.getId(), "FeeConfigured",
                Map.of("feePercent", command.feePercent().toPlainString(),
                        "feeFixed", command.feeFixed(),
                        "tenantId", command.tenantId()),
                command.tenantId());

        log.info("Fee configured: account={}, percent={}, fixed={}",
                account.getId(), command.feePercent(), command.feeFixed());

        return new FeeConfigResult(
                account.getId(), account.getPlatformFeePercent(), account.getPlatformFeeFixed());
    }

    @Override
    @Transactional(readOnly = true)
    public FeeConfigResult getFeeConfig(String accountId, String tenantId) {
        // SEC-BATCH-1: tenant-scoped read.
        ConnectedAccount account = TenantOwnership.require(
                repository.findAccountById(accountId, tenantId), "Connected account");

        return new FeeConfigResult(
                account.getId(), account.getPlatformFeePercent(), account.getPlatformFeeFixed());
    }
}
