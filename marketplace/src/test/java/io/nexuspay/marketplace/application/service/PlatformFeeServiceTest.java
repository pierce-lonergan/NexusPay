package io.nexuspay.marketplace.application.service;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.marketplace.application.port.in.ConfigureFeeUseCase;
import io.nexuspay.marketplace.application.port.out.MarketplaceEventPublisher;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.domain.ConnectedAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PlatformFeeService}.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@ExtendWith(MockitoExtension.class)
class PlatformFeeServiceTest {

    @Mock private MarketplaceRepository repository;
    @Mock private MarketplaceEventPublisher eventPublisher;

    private PlatformFeeService service;

    @BeforeEach
    void setUp() {
        service = new PlatformFeeService(repository, eventPublisher);
    }

    private static final String TENANT = "tenant-1";

    @Test
    void configureFee_updatesAccountAndPublishesEvent() {
        ConnectedAccount account = ConnectedAccount.create(TENANT, "Fee Corp", "f@test.com", "US", "USD");
        // SEC-BATCH-1: account is loaded tenant-scoped.
        when(repository.findAccountById(account.getId(), TENANT)).thenReturn(Optional.of(account));
        when(repository.saveAccount(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.configureFee(new ConfigureFeeUseCase.ConfigureFeeCommand(
                TENANT, account.getId(), new BigDecimal("2.50"), 30));

        assertEquals(new BigDecimal("2.50"), result.feePercent());
        assertEquals(30, result.feeFixed());
        verify(eventPublisher).publishEvent(eq("ConnectedAccount"), any(), eq("FeeConfigured"), any(), eq(TENANT));
    }

    @Test
    void configureFee_throwsWhenAccountNotFound() {
        when(repository.findAccountById("ca_missing", TENANT)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.configureFee(new ConfigureFeeUseCase.ConfigureFeeCommand(
                        TENANT, "ca_missing", new BigDecimal("2.50"), 30)));
    }

    @Test
    void configureFee_crossTenant_throwsNotFound() {
        // SEC-BATCH-1: account owned by tenant-2 → empty for tenant-1 → 404, no mutation.
        when(repository.findAccountById("ca_foreign", TENANT)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.configureFee(new ConfigureFeeUseCase.ConfigureFeeCommand(
                        TENANT, "ca_foreign", new BigDecimal("9.99"), 0)));
        verify(repository, never()).saveAccount(any());
    }

    @Test
    void getFeeConfig_returnsCurrentRates() {
        ConnectedAccount account = ConnectedAccount.create(TENANT, "Check Corp", "c@test.com", "US", "USD");
        account.setPlatformFeePercent(new BigDecimal("5.00"));
        account.setPlatformFeeFixed(50);
        when(repository.findAccountById(account.getId(), TENANT)).thenReturn(Optional.of(account));

        var result = service.getFeeConfig(account.getId(), TENANT);
        assertEquals(new BigDecimal("5.00"), result.feePercent());
        assertEquals(50, result.feeFixed());
    }

    @Test
    void getFeeConfig_crossTenant_throwsNotFound() {
        when(repository.findAccountById("ca_foreign", TENANT)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getFeeConfig("ca_foreign", TENANT));
    }
}
