package io.nexuspay.marketplace.application.service;

import io.nexuspay.common.exception.ResourceNotFoundException;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase;
import io.nexuspay.marketplace.application.port.out.MarketplaceEventPublisher;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SplitPaymentService}.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@ExtendWith(MockitoExtension.class)
class SplitPaymentServiceTest {

    @Mock private MarketplaceRepository repository;
    @Mock private MarketplaceEventPublisher eventPublisher;

    private SplitPaymentService service;

    private static final String TENANT = "tenant-1";

    @BeforeEach
    void setUp() {
        service = new SplitPaymentService(repository, eventPublisher);
    }

    @Test
    void createSplitPayment_withPercentageRules() {
        ConnectedAccount merchant = createAccount("merchant-1", BigDecimal.ZERO, 0);
        ConnectedAccount partner = createAccount("partner-1", BigDecimal.ZERO, 0);

        // SEC-BATCH-1: each referenced account is loaded tenant-scoped (id + caller tenant).
        when(repository.findAccountById("merchant-1", TENANT)).thenReturn(Optional.of(merchant));
        when(repository.findAccountById("partner-1", TENANT)).thenReturn(Optional.of(partner));
        when(repository.saveSplitPayment(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.saveSplitRule(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createSplitPayment(new CreateSplitPaymentUseCase.CreateSplitCommand(
                TENANT, "pi_abc123", 10000, "USD",
                List.of(
                        new CreateSplitPaymentUseCase.SplitRuleCommand("merchant-1", SplitType.PERCENTAGE, 0, new BigDecimal("80")),
                        new CreateSplitPaymentUseCase.SplitRuleCommand("partner-1", SplitType.REMAINDER, 0, null)
                )));

        assertNotNull(result.splitPaymentId());
        assertEquals("pi_abc123", result.paymentId());
        assertEquals(SplitPaymentStatus.PROCESSING, result.status());
        assertEquals(10000, result.totalAmount());
        assertEquals(2, result.rules().size());
        assertEquals(0, result.platformFeeAmount());

        verify(eventPublisher).publishEvent(eq("SplitPayment"), any(), eq("SplitPaymentCreated"), any(), eq(TENANT));
    }

    @Test
    void createSplitPayment_withPlatformFee() {
        ConnectedAccount merchant = createAccount("merchant-1", new BigDecimal("15"), 0);
        when(repository.findAccountById("merchant-1", TENANT)).thenReturn(Optional.of(merchant));
        when(repository.saveSplitPayment(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.saveSplitRule(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.savePlatformFee(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createSplitPayment(new CreateSplitPaymentUseCase.CreateSplitCommand(
                TENANT, "pi_fee123", 10000, "USD",
                List.of(
                        new CreateSplitPaymentUseCase.SplitRuleCommand("merchant-1", SplitType.REMAINDER, 0, null)
                )));

        assertEquals(1500, result.platformFeeAmount()); // 15% of 10000
        verify(repository).savePlatformFee(any());
    }

    @Test
    void createSplitPayment_foreignReferencedAccount_throwsNotFound() {
        // SEC-BATCH-1: a split rule referencing an account owned by tenant-2. The tenant-scoped finder
        // returns empty for the tenant-1 caller → 404 → no split crediting a foreign account is created.
        when(repository.saveSplitPayment(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findAccountById("ca_foreign", TENANT)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.createSplitPayment(new CreateSplitPaymentUseCase.CreateSplitCommand(
                        TENANT, "pi_evil", 10000, "USD",
                        List.of(new CreateSplitPaymentUseCase.SplitRuleCommand(
                                "ca_foreign", SplitType.REMAINDER, 0, null)))));
        verify(repository, never()).saveSplitRule(any());
    }

    @Test
    void createSplitPayment_rejectsEmptyRules() {
        assertThrows(IllegalArgumentException.class, () ->
                service.createSplitPayment(new CreateSplitPaymentUseCase.CreateSplitCommand(
                        TENANT, "pi_bad", 10000, "USD", List.of())));
    }

    @Test
    void createSplitPayment_rejectsMultipleRemainderRules() {
        assertThrows(IllegalArgumentException.class, () ->
                service.createSplitPayment(new CreateSplitPaymentUseCase.CreateSplitCommand(
                        TENANT, "pi_bad", 10000, "USD",
                        List.of(
                                new CreateSplitPaymentUseCase.SplitRuleCommand("a1", SplitType.REMAINDER, 0, null),
                                new CreateSplitPaymentUseCase.SplitRuleCommand("a2", SplitType.REMAINDER, 0, null)
                        ))));
    }

    @Test
    void getSplitPayment_returnsDetailsWithRules() {
        SplitPayment sp = SplitPayment.create("pi_get123", TENANT, 10000, "USD");
        SplitRule rule = SplitRule.create(sp.getId(), "merchant-1", SplitType.REMAINDER, 0, null, "USD");
        rule.setCalculatedAmount(10000);
        sp.addRule(rule);

        when(repository.findSplitPaymentById(sp.getId(), TENANT)).thenReturn(Optional.of(sp));
        when(repository.findFeesBySplitPaymentId(sp.getId())).thenReturn(Optional.empty());

        var result = service.getSplitPayment(sp.getId(), TENANT);
        assertEquals(sp.getId(), result.splitPaymentId());
        assertEquals(1, result.rules().size());
        assertEquals(0, result.platformFeeAmount());
    }

    @Test
    void getSplitPayment_crossTenant_throwsNotFound() {
        // SEC-BATCH-1: split payment owned by tenant-2 → empty for tenant-1 → 404.
        when(repository.findSplitPaymentById("sp_foreign", TENANT)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getSplitPayment("sp_foreign", TENANT));
    }

    private ConnectedAccount createAccount(String id, BigDecimal feePercent, long feeFixed) {
        ConnectedAccount a = ConnectedAccount.create(TENANT, "Test Biz", "t@test.com", "US", "USD");
        a.setId(id);
        a.setPlatformFeePercent(feePercent);
        a.setPlatformFeeFixed(feeFixed);
        return a;
    }
}
