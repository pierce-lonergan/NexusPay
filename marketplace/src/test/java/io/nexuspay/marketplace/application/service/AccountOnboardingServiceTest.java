package io.nexuspay.marketplace.application.service;

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
                "tenant-1", "Acme Corp", "acme@test.com", "US", "USD", PayoutSchedule.WEEKLY));

        assertNotNull(result.accountId());
        assertTrue(result.accountId().startsWith("ca_"));
        assertEquals("Acme Corp", result.businessName());
        assertEquals(AccountState.ONBOARDING, result.status());
        assertEquals(KycStatus.IN_REVIEW, result.kycStatus());

        verify(repository, times(2)).saveAccount(any());
        verify(kycProvider).initiateVerification(any());
        verify(eventPublisher).publishEvent(eq("ConnectedAccount"), any(), eq("AccountOnboarded"), any(), eq("tenant-1"));
    }

    @Test
    void onboardAccount_continuesWhenKycFails() {
        when(repository.saveAccount(any())).thenAnswer(inv -> inv.getArgument(0));
        when(kycProvider.initiateVerification(any())).thenThrow(new RuntimeException("KYC service unavailable"));

        var result = service.onboardAccount(new OnboardAccountUseCase.OnboardCommand(
                "tenant-1", "Fallback Corp", "fb@test.com", "US", "USD", null));

        assertNotNull(result.accountId());
        assertEquals(AccountState.ONBOARDING, result.status());
        assertEquals(KycStatus.PENDING, result.kycStatus());
    }

    @Test
    void getAccount_returnsAccountInfo() {
        ConnectedAccount account = ConnectedAccount.create("tenant-1", "Test Biz", "test@test.com", "US", "USD");
        when(repository.findAccountById(account.getId())).thenReturn(Optional.of(account));

        var info = service.getAccount(account.getId(), "tenant-1");

        assertEquals(account.getId(), info.accountId());
        assertEquals("Test Biz", info.businessName());
    }

    @Test
    void getAccount_throwsWhenNotFound() {
        when(repository.findAccountById("ca_missing")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.getAccount("ca_missing", "tenant-1"));
    }

    @Test
    void suspendAccount_updatesStatusAndPublishesEvent() {
        ConnectedAccount account = ConnectedAccount.create("tenant-1", "Suspend Me", "s@test.com", "US", "USD");
        when(repository.findAccountById(account.getId())).thenReturn(Optional.of(account));
        when(repository.saveAccount(any())).thenAnswer(inv -> inv.getArgument(0));

        service.suspendAccount(account.getId(), "tenant-1", "Fraud detected");

        ArgumentCaptor<ConnectedAccount> captor = ArgumentCaptor.forClass(ConnectedAccount.class);
        verify(repository).saveAccount(captor.capture());
        assertEquals(AccountState.SUSPENDED, captor.getValue().getStatus());
        verify(eventPublisher).publishEvent(eq("ConnectedAccount"), any(), eq("AccountSuspended"), any(), eq("tenant-1"));
    }

    @Test
    void closeAccount_updatesStatusAndPublishesEvent() {
        ConnectedAccount account = ConnectedAccount.create("tenant-1", "Close Me", "c@test.com", "US", "USD");
        when(repository.findAccountById(account.getId())).thenReturn(Optional.of(account));
        when(repository.saveAccount(any())).thenAnswer(inv -> inv.getArgument(0));

        service.closeAccount(account.getId(), "tenant-1");

        ArgumentCaptor<ConnectedAccount> captor = ArgumentCaptor.forClass(ConnectedAccount.class);
        verify(repository).saveAccount(captor.capture());
        assertEquals(AccountState.CLOSED, captor.getValue().getStatus());
    }

    @Test
    void updateAccount_appliesChanges() {
        ConnectedAccount account = ConnectedAccount.create("tenant-1", "Old Name", "old@test.com", "US", "USD");
        when(repository.findAccountById(account.getId())).thenReturn(Optional.of(account));
        when(repository.saveAccount(any())).thenAnswer(inv -> inv.getArgument(0));

        var info = service.updateAccount(account.getId(), "tenant-1",
                new OnboardAccountUseCase.UpdateAccountCommand(
                        "New Name", "new@test.com", PayoutSchedule.MONTHLY, 5000, null, 100));

        assertEquals("New Name", info.businessName());
        assertEquals("new@test.com", info.email());
        assertEquals(PayoutSchedule.MONTHLY, info.payoutSchedule());
        assertEquals(5000, info.payoutMinimum());
    }
}
