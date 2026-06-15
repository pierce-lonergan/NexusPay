package io.nexuspay.marketplace.application.service;

import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.marketplace.application.port.in.OnboardAccountUseCase;
import io.nexuspay.marketplace.application.port.out.KycProviderPort;
import io.nexuspay.marketplace.application.port.out.MarketplaceEventPublisher;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.domain.ConnectedAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Service for connected account onboarding, management, and KYC coordination.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Service
public class AccountOnboardingService implements OnboardAccountUseCase {

    private static final Logger log = LoggerFactory.getLogger(AccountOnboardingService.class);

    private final MarketplaceRepository repository;
    private final KycProviderPort kycProvider;
    private final MarketplaceEventPublisher eventPublisher;

    public AccountOnboardingService(MarketplaceRepository repository,
                                     KycProviderPort kycProvider,
                                     MarketplaceEventPublisher eventPublisher) {
        this.repository = repository;
        this.kycProvider = kycProvider;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public OnboardResult onboardAccount(OnboardCommand command) {
        ConnectedAccount account = ConnectedAccount.create(
                command.tenantId(), command.businessName(), command.email(),
                command.country(), command.defaultCurrency());

        if (command.payoutSchedule() != null) {
            account.setPayoutSchedule(command.payoutSchedule());
        }

        account = repository.saveAccount(account);

        // Initiate KYC verification asynchronously
        try {
            var kycResult = kycProvider.initiateVerification(
                    new KycProviderPort.KycVerificationRequest(
                            account.getId(), account.getBusinessName(),
                            account.getEmail(), account.getCountry()));
            account.updateKycStatus(kycResult.status());
            account = repository.saveAccount(account);
            log.info("KYC initiated for account={}, verificationRef={}", account.getId(), kycResult.verificationReference());
        } catch (Exception e) {
            log.warn("KYC initiation failed for account={}, will retry: {}", account.getId(), e.getMessage());
        }

        eventPublisher.publishEvent("ConnectedAccount", account.getId(), "AccountOnboarded",
                Map.of("businessName", account.getBusinessName(),
                        "country", account.getCountry(),
                        "status", account.getStatus().name(),
                        "tenantId", command.tenantId()),
                command.tenantId());

        log.info("Account onboarded: id={}, business={}, tenant={}",
                account.getId(), account.getBusinessName(), command.tenantId());

        return new OnboardResult(
                account.getId(), account.getBusinessName(),
                account.getStatus(), account.getKycStatus());
    }

    @Override
    @Transactional(readOnly = true)
    public AccountInfo getAccount(String accountId, String tenantId) {
        // SEC-BATCH-1: tenant-scoped read — 404 on absent OR wrong-tenant.
        ConnectedAccount account = TenantOwnership.require(
                repository.findAccountById(accountId, tenantId), "Connected account");
        return toAccountInfo(account);
    }

    @Override
    @Transactional
    public AccountInfo updateAccount(String accountId, String tenantId, UpdateAccountCommand command) {
        // SEC-BATCH-1: tenant-scoped write.
        ConnectedAccount account = TenantOwnership.require(
                repository.findAccountById(accountId, tenantId), "Connected account");

        if (command.businessName() != null) account.setBusinessName(command.businessName());
        if (command.email() != null) account.setEmail(command.email());
        if (command.payoutSchedule() != null) account.setPayoutSchedule(command.payoutSchedule());
        account.setPayoutMinimum(command.payoutMinimum());
        if (command.platformFeePercent() != null) account.setPlatformFeePercent(command.platformFeePercent());
        account.setPlatformFeeFixed(command.platformFeeFixed());

        account = repository.saveAccount(account);

        eventPublisher.publishEvent("ConnectedAccount", accountId, "AccountUpdated",
                Map.of("tenantId", tenantId), tenantId);

        log.info("Account updated: id={}, tenant={}", accountId, tenantId);
        return toAccountInfo(account);
    }

    @Override
    @Transactional
    public void suspendAccount(String accountId, String tenantId, String reason) {
        // SEC-BATCH-1: tenant-scoped lifecycle write (cross-tenant suspend is a high-impact attack).
        ConnectedAccount account = TenantOwnership.require(
                repository.findAccountById(accountId, tenantId), "Connected account");

        account.suspend(reason);
        repository.saveAccount(account);

        eventPublisher.publishEvent("ConnectedAccount", accountId, "AccountSuspended",
                Map.of("reason", reason, "tenantId", tenantId), tenantId);

        log.info("Account suspended: id={}, reason={}", accountId, reason);
    }

    @Override
    @Transactional
    public void closeAccount(String accountId, String tenantId) {
        // SEC-BATCH-1: tenant-scoped lifecycle write.
        ConnectedAccount account = TenantOwnership.require(
                repository.findAccountById(accountId, tenantId), "Connected account");

        account.close();
        repository.saveAccount(account);

        eventPublisher.publishEvent("ConnectedAccount", accountId, "AccountClosed",
                Map.of("tenantId", tenantId), tenantId);

        log.info("Account closed: id={}, tenant={}", accountId, tenantId);
    }

    private AccountInfo toAccountInfo(ConnectedAccount a) {
        return new AccountInfo(
                a.getId(), a.getTenantId(), a.getBusinessName(), a.getEmail(),
                a.getStatus(), a.getKycStatus(), a.getCountry(), a.getDefaultCurrency(),
                a.getPayoutSchedule(), a.getPayoutMinimum(),
                a.getPlatformFeePercent(), a.getPlatformFeeFixed(),
                a.getCreatedAt(), a.getUpdatedAt());
    }
}
