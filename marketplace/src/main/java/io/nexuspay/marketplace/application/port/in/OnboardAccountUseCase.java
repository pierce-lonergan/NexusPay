package io.nexuspay.marketplace.application.port.in;

import io.nexuspay.marketplace.domain.AccountState;
import io.nexuspay.marketplace.domain.KycStatus;
import io.nexuspay.marketplace.domain.PayoutSchedule;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Use case for onboarding, retrieving, and managing connected accounts.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface OnboardAccountUseCase {

    OnboardResult onboardAccount(OnboardCommand command);

    AccountInfo getAccount(String accountId, String tenantId);

    AccountInfo updateAccount(String accountId, String tenantId, UpdateAccountCommand command);

    void suspendAccount(String accountId, String tenantId, String reason);

    void closeAccount(String accountId, String tenantId);

    record OnboardCommand(
            String tenantId,
            String businessName,
            String email,
            String country,
            String defaultCurrency,
            PayoutSchedule payoutSchedule
    ) {}

    record OnboardResult(
            String accountId,
            String businessName,
            AccountState status,
            KycStatus kycStatus
    ) {}

    record UpdateAccountCommand(
            String businessName,
            String email,
            PayoutSchedule payoutSchedule,
            long payoutMinimum,
            BigDecimal platformFeePercent,
            long platformFeeFixed
    ) {}

    record AccountInfo(
            String accountId,
            String tenantId,
            String businessName,
            String email,
            AccountState status,
            KycStatus kycStatus,
            String country,
            String defaultCurrency,
            PayoutSchedule payoutSchedule,
            long payoutMinimum,
            BigDecimal platformFeePercent,
            long platformFeeFixed,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
