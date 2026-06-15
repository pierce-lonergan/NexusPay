package io.nexuspay.marketplace.application.service;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.marketplace.application.port.in.OnboardAccountUseCase;
import io.nexuspay.marketplace.application.port.out.KycProviderPort;
import io.nexuspay.marketplace.application.port.out.MarketplaceEventPublisher;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AccountOnboardingService}.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@ExtendWith(MockitoExtension.class)
class AccountOnboardingServiceTest {

    @Mock private MarketplaceRepository repository;
    @Mock private KycProviderPort kycProvider;
    @Mock private MarketplaceEventPublisher eventPublisher;

    private AccountOnboardingService service;

    private static final String TENANT = "tenant-1";

    @BeforeEach
    void setUp() {
        service = new AccountOnboardingService(repository, kycProvider, eventPublisher);
    }

    @Test
    void onboardAccount_createsAccountAndInitiatesKyc() {
        when(repository.saveAccount(any())).thenAnswer(inv -> inv.getArgument(0));
        when(kycProvider.initiateVerification(any())).thenReturn(
                new KycProviderPort.KycVerificationResult("ref-123", KycStatus.IN_REVIEW, "https://kyc.test/ref-123"));

        var result = service.onboardAccount(new OnboardAccountUseCase.OnboardCommand(
                TENANT, "Acme Corp", "acme@test.com", "US", "USD", PayoutSchedule.WEEKLY));

        assertNotNull(result.accountId());
        assertTrue(result.accountId().startsWith("ca_"));
        assertEquals("Acme Corp", result.businessName());
        assertEquals(AccountState.ONBOARDING, result.status());
        assertEquals(KycStatus.IN_REVIEW, result.kycStatus());

        verify(repository, times(2)).saveAccount(any());
        verify(kycProvider).initiateVerification(any());
        verify(eventPublisher).publishEvent(eq("ConnectedAccount"), any(), eq("AccountOnboarded"), any(), eq(TENANT));
    }

    @Test
    void onboardAccount_continuesWhenKycFails() {
        when(repository.saveAccount(any())).thenAnswer(inv -> inv.getArgument(0));
        when(kycProvider.initiateVerification(any())).thenThrow(new RuntimeException("KYC service unavailable"));

        var result = service.onboardAccount(new OnboardAccountUseCase.OnboardCommand(
                TENANT, "Fallback Corp", "fb@test.com", "US", "USD", null));

        assertNotNull(result.accountId());
        assertEquals(AccountState.ONBOARDING, result.status());
        assertEquals(KycStatus.PENDING, result.kycStatus());
    }

    @Test
    void getAccount_returnsAccountInfo() {
        ConnectedAccount account = ConnectedAccount.create(TENANT, "Test Biz", "test@test.com", "US", "USD");
        when(repository.findAccountById(account.getId(), TENANT)).thenReturn(Optional.of(account));

        var info = service.getAccount(account.getId(), TENANT);

        assertEquals(account.getId(), info.accountId());
        assertEquals("Test Biz", info.businessName());
    }

    @Test
    void getAccount_throwsWhenNotFound() {
        when(repository.findAccountById("ca_missing", TENANT)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getAccount("ca_missing", TENANT));
    }

    @Test
    void getAccount_crossTenant_throwsNotFound() {
        // SEC-BATCH-1: account owned by tenant-2 → tenant-scoped finder empty for tenant-1 → 404.
        when(repository.findAccountById("ca_foreign", TENANT)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getAccount("ca_foreign", TENANT));
    }

    @Test
    void updateAccount_crossTenant_throwsNotFound() {
        when(repository.findAccountById("ca_foreign", TENANT)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.updateAccount("ca_foreign", TENANT,
                new OnboardAccountUseCase.UpdateAccountCommand(
                        "New Name", "new@test.com", PayoutSchedule.MONTHLY, 5000, null, 100)));
        verify(repository, never()).saveAccount(any());
    }

    @Test
    void suspendAccount_updatesStatusAndPublishesEvent() {
        ConnectedAccount account = ConnectedAccount.create(TENANT, "Suspend Me", "s@test.com", "US", "USD");
        when(repository.findAccountById(account.getId(), TENANT)).thenReturn(Optional.of(account));
        when(repository.saveAccount(any())).thenAnswer(inv -> inv.getArgument(0));

        service.suspendAccount(account.getId(), TENANT, "Fraud detected");

        ArgumentCaptor<ConnectedAccount> captor = ArgumentCaptor.forClass(ConnectedAccount.class);
        verify(repository).saveAccount(captor.capture());
        assertEquals(AccountState.SUSPENDED, captor.getValue().getStatus());
        verify(eventPublisher).publishEvent(eq("ConnectedAccount"), any(), eq("AccountSuspended"), any(), eq(TENANT));
    }

    @Test
    void suspendAccount_crossTenant_throwsNotFound() {
        // SEC-BATCH-1: cross-tenant suspend is among the worst writes — must 404 and never mutate.
        when(repository.findAccountById("ca_foreign", TENANT)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.suspendAccount("ca_foreign", TENANT, "malicious suspend"));
        verify(repository, never()).saveAccount(any());
    }

    @Test
    void closeAccount_updatesStatusAndPublishesEvent() {
        ConnectedAccount account = ConnectedAccount.create(TENANT, "Close Me", "c@test.com", "US", "USD");
        when(repository.findAccountById(account.getId(), TENANT)).thenReturn(Optional.of(account));
        when(repository.saveAccount(any())).thenAnswer(inv -> inv.getArgument(0));

        service.closeAccount(account.getId(), TENANT);

        ArgumentCaptor<ConnectedAccount> captor = ArgumentCaptor.forClass(ConnectedAccount.class);
        verify(repository).saveAccount(captor.capture());
        assertEquals(AccountState.CLOSED, captor.getValue().getStatus());
    }

    @Test
    void closeAccount_crossTenant_throwsNotFound() {
        when(repository.findAccountById("ca_foreign", TENANT)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.closeAccount("ca_foreign", TENANT));
        verify(repository, never()).saveAccount(any());
    }

    @Test
    void updateAccount_appliesChanges() {
        ConnectedAccount account = ConnectedAccount.create(TENANT, "Old Name", "old@test.com", "US", "USD");
        when(repository.findAccountById(account.getId(), TENANT)).thenReturn(Optional.of(account));
        when(repository.saveAccount(any())).thenAnswer(inv -> inv.getArgument(0));

        var info = service.updateAccount(account.getId(), TENANT,
                new OnboardAccountUseCase.UpdateAccountCommand(
                        "New Name", "new@test.com", PayoutSchedule.MONTHLY, 5000, null, 100));

        assertEquals("New Name", info.businessName());
        assertEquals("new@test.com", info.email());
        assertEquals(PayoutSchedule.MONTHLY, info.payoutSchedule());
        assertEquals(5000, info.payoutMinimum());
    }
}
