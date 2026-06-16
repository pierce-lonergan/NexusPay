package io.nexuspay.marketplace.application.service;

import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service for creating and managing split payments across connected accounts.
 * Handles split rule resolution, platform fee calculation, and ledger distribution.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Service
public class SplitPaymentService implements CreateSplitPaymentUseCase {

    private static final Logger log = LoggerFactory.getLogger(SplitPaymentService.class);

    private final MarketplaceRepository repository;
    private final SplitPaymentWriter writer;

    public SplitPaymentService(MarketplaceRepository repository,
                                SplitPaymentWriter writer) {
        this.repository = repository;
        this.writer = writer;
    }

    @Override
    @Transactional
    public SplitPaymentResult createSplitPayment(CreateSplitCommand command) {
        validateSplitRules(command.rules());

        // SEC-20: idempotency read-through. A retried create for the same (tenant, payment) must NOT
        // double-create the split row tree — return the existing split mapped to a result. The V4034
        // UNIQUE(tenant_id, payment_id) is the concurrency backstop for retries that race past this read
        // (handled below via DataIntegrityViolationException).
        var existing = repository.findSplitPaymentByTenantAndPaymentId(
                command.tenantId(), command.paymentId());
        if (existing.isPresent()) {
            log.info("Split payment already exists, returning idempotently: id={}, payment={}, tenant={}",
                    existing.get().getId(), command.paymentId(), command.tenantId());
            return toResult(existing.get());
        }

        try {
            // SEC-20: the write runs in its OWN (REQUIRES_NEW) transaction so that a unique-violation
            // rollback does NOT poison THIS transaction — letting the re-fetch below run cleanly.
            return writer.create(command);
        } catch (DataIntegrityViolationException e) {
            // SEC-BATCH-5c (idempotency SHOULD_FIX): only swallow the SPECIFIC (tenant_id, payment_id)
            // uniqueness race. The writer's REQUIRES_NEW transaction can raise a
            // DataIntegrityViolationException for OTHER reasons (e.g. the split_rules FK firing when a
            // referenced connected_accounts row is concurrently deleted, or a future NOT-NULL/check/other
            // unique constraint). Treating those as the benign race would re-fetch an empty Optional ->
            // 404, MASKING a genuine integrity bug in a money path. Mirror FraudAssessmentService: re-throw
            // anything that is not the tenant/payment unique violation.
            if (!isTenantPaymentConstraintViolation(e)) {
                throw e;
            }
            // SEC-20: concurrent retry — two callers both passed the pre-check, the UNIQUE rejected the
            // loser's insert (its REQUIRES_NEW tx rolled back). Re-fetch and return the winner's split
            // idempotently in this still-clean transaction; never surface a 500.
            SplitPayment winner = TenantOwnership.require(
                    repository.findSplitPaymentByTenantAndPaymentId(command.tenantId(), command.paymentId()),
                    "Split payment");
            log.info("Concurrent split-payment create lost the unique race, returning existing: "
                    + "id={}, payment={}, tenant={}", winner.getId(), command.paymentId(), command.tenantId());
            return toResult(winner);
        }
    }

    /** Name of the unique index that enforces (tenant_id, payment_id) uniqueness (V4034). */
    private static final String TENANT_PAYMENT_CONSTRAINT = "uq_split_payments_tenant_payment";

    /**
     * SEC-BATCH-5c: narrow the SEC-20 backstop catch to the SPECIFIC unique-constraint race so an
     * unrelated integrity error is not swallowed as a benign duplicate (mirrors
     * {@code FraudAssessmentService.isTenantIdemConstraintViolation}). Two complementary signals:
     * <ul>
     *   <li>Spring's {@link DuplicateKeyException} — the dedicated duplicate-key subtype the JPA
     *       exception translator raises for a unique violation (PostgreSQL SQLSTATE 23505); or</li>
     *   <li>the {@link #TENANT_PAYMENT_CONSTRAINT} name appearing anywhere in the exception chain's
     *       messages — covers a bare {@link DataIntegrityViolationException} carrying the constraint
     *       name.</li>
     * </ul>
     * Any other {@link DataIntegrityViolationException} (a different unique index, a NOT-NULL/FK
     * violation, ...) returns {@code false} and is re-thrown by the caller.
     */
    private static boolean isTenantPaymentConstraintViolation(DataIntegrityViolationException ex) {
        if (ex instanceof DuplicateKeyException) {
            return true;
        }
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains(TENANT_PAYMENT_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public SplitPaymentResult getSplitPayment(String splitPaymentId, String tenantId) {
        // SEC-BATCH-1: tenant-scoped by-id read — 404 on absent OR wrong-tenant.
        SplitPayment sp = TenantOwnership.require(
                repository.findSplitPaymentById(splitPaymentId, tenantId), "Split payment");
        return toResult(sp);
    }

    /**
     * SEC-20: maps a persisted {@link SplitPayment} (with its rules loaded) plus its recorded platform
     * fee to a {@link SplitPaymentResult}. Shared by {@link #getSplitPayment} and the idempotent
     * re-return paths of {@link #createSplitPayment}.
     */
    private SplitPaymentResult toResult(SplitPayment sp) {
        List<SplitRuleResult> ruleResults = sp.getRules().stream()
                .map(r -> new SplitRuleResult(
                        r.getId(), r.getConnectedAccountId(),
                        r.getSplitType(), r.getCalculatedAmount(), r.getCurrency()))
                .toList();

        long feeAmount = repository.findFeesBySplitPaymentId(sp.getId())
                .map(PlatformFee::getFeeAmount).orElse(0L);

        return new SplitPaymentResult(
                sp.getId(), sp.getPaymentId(), sp.getStatus(),
                sp.getTotalAmount(), sp.getCurrency(), ruleResults, feeAmount);
    }

    private void validateSplitRules(List<SplitRuleCommand> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("Split payment must have at least one rule");
        }

        long remainderCount = rules.stream()
                .filter(r -> r.splitType() == SplitType.REMAINDER)
                .count();
        if (remainderCount > 1) {
            throw new IllegalArgumentException("Split payment can have at most one REMAINDER rule");
        }
    }
}
