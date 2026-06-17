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
import org.springframework.dao.DataIntegrityViolationException;

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
        // SEC-20: the create path delegates to a REQUIRES_NEW SplitPaymentWriter. We wire a REAL writer
        // over the mocked repo/publisher so the full create flow (rules, fee, event) is still exercised
        // through the service, while the service's read-through dedup + unique-race re-fetch are unit
        // tested directly. (Propagation is a no-op without a real tx manager — the throw from the mocked
        // save still propagates synchronously into the service's catch, which is what we assert.)
        SplitPaymentWriter writer = new SplitPaymentWriter(repository, eventPublisher);
        service = new SplitPaymentService(repository, writer);
    }

    @Test
    void createSplitPayment_withPercentageRules() {
        ConnectedAccount merchant = createAccount("merchant-1", BigDecimal.ZERO, 0);
        ConnectedAccount partner = createAccount("partner-1", BigDecimal.ZERO, 0);

        // SEC-BATCH-1: each referenced account is loaded tenant-scoped (id + caller tenant).
        when(repository.findAccountById("merchant-1", TENANT)).thenReturn(Optional.of(merchant));
        when(repository.findAccountById("partner-1", TENANT)).thenReturn(Optional.of(partner));
        // SEC-20: the writer's FIRST parent write is saveAndFlushSplitPayment; the final markProcessing
        // write is saveSplitPayment.
        when(repository.saveAndFlushSplitPayment(any())).thenAnswer(inv -> inv.getArgument(0));
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
        when(repository.saveAndFlushSplitPayment(any())).thenAnswer(inv -> inv.getArgument(0));
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
    void createSplitPayment_platformFeeExceedsTotal_isRejected_noNegativeLegPersisted() {
        // SEC-28: a platform fee that meets/exceeds the payment total would drive the distributable amount
        // (and every per-account payout leg) NEGATIVE. The writer must reject before persisting any rule or
        // fee. 150% of 10000 = 15000 > 10000. On the pre-SEC-28 code this produced a negative remainder leg.
        ConnectedAccount merchant = createAccount("merchant-1", new BigDecimal("150"), 0);
        when(repository.findAccountById("merchant-1", TENANT)).thenReturn(Optional.of(merchant));
        when(repository.saveAndFlushSplitPayment(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(IllegalArgumentException.class, () ->
                service.createSplitPayment(new CreateSplitPaymentUseCase.CreateSplitCommand(
                        TENANT, "pi_feebomb", 10000, "USD",
                        List.of(new CreateSplitPaymentUseCase.SplitRuleCommand(
                                "merchant-1", SplitType.REMAINDER, 0, null)))));

        verify(repository, never()).saveSplitRule(any());
        verify(repository, never()).savePlatformFee(any());
    }

    @Test
    void createSplitPayment_foreignReferencedAccount_throwsNotFound() {
        // SEC-BATCH-1: a split rule referencing an account owned by tenant-2. The tenant-scoped finder
        // returns empty for the tenant-1 caller → 404 → no split crediting a foreign account is created.
        when(repository.saveAndFlushSplitPayment(any())).thenAnswer(inv -> inv.getArgument(0));
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
    void createSplitPayment_idempotent_returnsExistingWithoutRecreating() {
        // SEC-20: a retry for the same (tenant, payment) must return the existing split and NEVER write
        // a new split row tree. The read-through finds it and short-circuits.
        SplitPayment existing = SplitPayment.create("pi_dup", TENANT, 10_000, "USD");
        existing.markProcessing();
        SplitRule rule = SplitRule.create(existing.getId(), "merchant-1", SplitType.REMAINDER, 0, null, "USD");
        rule.setCalculatedAmount(10_000);
        existing.addRule(rule);

        when(repository.findSplitPaymentByTenantAndPaymentId(TENANT, "pi_dup"))
                .thenReturn(Optional.of(existing));
        when(repository.findFeesBySplitPaymentId(existing.getId())).thenReturn(Optional.empty());

        var result = service.createSplitPayment(new CreateSplitPaymentUseCase.CreateSplitCommand(
                TENANT, "pi_dup", 10_000, "USD",
                List.of(new CreateSplitPaymentUseCase.SplitRuleCommand(
                        "merchant-1", SplitType.REMAINDER, 0, null))));

        assertEquals(existing.getId(), result.splitPaymentId());
        assertEquals(SplitPaymentStatus.PROCESSING, result.status());
        assertEquals(1, result.rules().size());
        // No duplicate creation: the split/rule writers must NOT be touched on the idempotent path.
        verify(repository, never()).saveSplitPayment(any());
        verify(repository, never()).saveSplitRule(any());
        verify(eventPublisher, never()).publishEvent(any(), any(), any(), any(), any());
    }

    @Test
    void createSplitPayment_concurrentRace_uniqueViolation_refetchesAndReturnsExisting() {
        // SEC-20: two callers both pass the pre-check; the UNIQUE(tenant_id, payment_id) (V4034) rejects
        // the loser's insert with DataIntegrityViolationException. The service must catch it, re-fetch the
        // winner's split, and return it idempotently — never surface a 500.
        SplitPayment winner = SplitPayment.create("pi_race", TENANT, 10_000, "USD");
        winner.markProcessing();
        SplitRule rule = SplitRule.create(winner.getId(), "merchant-1", SplitType.REMAINDER, 0, null, "USD");
        rule.setCalculatedAmount(10_000);
        winner.addRule(rule);

        // First read (pre-check) returns empty -> we proceed to create; the second read (after the
        // unique violation) returns the winner.
        when(repository.findSplitPaymentByTenantAndPaymentId(TENANT, "pi_race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.findFeesBySplitPaymentId(winner.getId())).thenReturn(Optional.empty());
        // The optimistic insert path trips the unique constraint on the FIRST write — the writer's
        // saveAndFlushSplitPayment (flushed so the violation surfaces synchronously). The per-rule
        // account lookups are never reached on this path.
        when(repository.saveAndFlushSplitPayment(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key uq_split_payments_tenant_payment"));

        var result = service.createSplitPayment(new CreateSplitPaymentUseCase.CreateSplitCommand(
                TENANT, "pi_race", 10_000, "USD",
                List.of(new CreateSplitPaymentUseCase.SplitRuleCommand(
                        "merchant-1", SplitType.REMAINDER, 0, null))));

        assertEquals(winner.getId(), result.splitPaymentId());
        assertEquals(SplitPaymentStatus.PROCESSING, result.status());
        // Re-fetched exactly: pre-check + post-violation re-read = 2 lookups.
        verify(repository, times(2)).findSplitPaymentByTenantAndPaymentId(TENANT, "pi_race");
    }

    @Test
    void createSplitPayment_unrelatedIntegrityViolation_isRethrown_notMaskedAs404() {
        // SEC-BATCH-5c: a DataIntegrityViolationException that is NOT the (tenant_id, payment_id) unique
        // race (e.g. an FK violation on a concurrently-deleted connected account) must PROPAGATE — not be
        // swallowed and relabelled as the benign race, which would re-fetch an empty Optional and surface
        // a misleading 404 while hiding a genuine integrity bug in a money path.
        when(repository.findSplitPaymentByTenantAndPaymentId(TENANT, "pi_fk"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlushSplitPayment(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "could not execute statement; fk violation fk_split_rules_connected_account"));

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class, () ->
                service.createSplitPayment(new CreateSplitPaymentUseCase.CreateSplitCommand(
                        TENANT, "pi_fk", 10_000, "USD",
                        List.of(new CreateSplitPaymentUseCase.SplitRuleCommand(
                                "merchant-1", SplitType.REMAINDER, 0, null)))));
        assertTrue(thrown.getMessage().contains("fk violation"));
        // The unrelated DIVE must NOT trigger the idempotent re-fetch path: only the pre-check read ran.
        verify(repository, times(1)).findSplitPaymentByTenantAndPaymentId(TENANT, "pi_fk");
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
