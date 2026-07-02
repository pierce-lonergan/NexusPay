package io.nexuspay.marketplace.application.service;

import io.nexuspay.common.tenant.CallerMode;
import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase.CreateSplitCommand;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase.SplitPaymentResult;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase.SplitRuleCommand;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase.SplitRuleResult;
import io.nexuspay.marketplace.application.port.out.LedgerPort;
import io.nexuspay.marketplace.application.port.out.MarketplaceEventPublisher;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.domain.ConnectedAccount;
import io.nexuspay.marketplace.domain.PlatformFee;
import io.nexuspay.marketplace.domain.SplitPayment;
import io.nexuspay.marketplace.domain.SplitRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SEC-20: the actual split-payment write, isolated in its OWN transaction.
 *
 * <p><b>Why a separate bean with {@link Propagation#REQUIRES_NEW}.</b> A truly-concurrent retry can
 * slip past {@code SplitPaymentService.createSplitPayment}'s read-through dedup (both callers read no
 * existing split, both attempt the insert). The {@code uq_split_payments_tenant_payment} UNIQUE
 * (V4034) then rejects the loser's flush with {@link org.springframework.dao.DataIntegrityViolationException}.
 * That violation marks the SURROUNDING transaction rollback-only, so the loser cannot re-read in the
 * same transaction. By running the write in a {@code REQUIRES_NEW} transaction, only THIS inner
 * transaction rolls back — the caller's outer transaction stays clean and can re-fetch the winning
 * split and return it idempotently (the split's identity is not deterministic, unlike fraud's
 * recomputed decision, so re-reading the winner is the only way to return the right id).</p>
 *
 * <p>The first (parent) row is written via {@code saveAndFlushSplitPayment} so the unique violation is
 * raised synchronously inside this transaction rather than deferred to commit (the entity has a
 * pre-assigned String {@code @Id}, so a plain {@code save()} merges and defers the INSERT).</p>
 */
@Component
class SplitPaymentWriter {

    private static final Logger log = LoggerFactory.getLogger(SplitPaymentWriter.class);

    private final MarketplaceRepository repository;
    private final MarketplaceEventPublisher eventPublisher;
    private final LedgerPort ledgerPort;

    SplitPaymentWriter(MarketplaceRepository repository, MarketplaceEventPublisher eventPublisher,
                       LedgerPort ledgerPort) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.ledgerPort = ledgerPort;
    }

    /**
     * Creates the split payment, its rules, and the platform fee in a fresh transaction. Throws
     * {@link org.springframework.dao.DataIntegrityViolationException} if a concurrent caller already
     * committed the same (tenant, payment) — the caller catches it and re-fetches the winner.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SplitPaymentResult create(CreateSplitCommand command) {
        // Look up the platform account to determine fee structure
        long platformFeeAmount = 0;

        // Create split payment. saveAndFlush forces the unique check NOW (SEC-20).
        SplitPayment splitPayment = SplitPayment.create(
                command.paymentId(), command.tenantId(),
                command.totalAmount(), command.currency());
        splitPayment = repository.saveAndFlushSplitPayment(splitPayment);

        // Create split rules and calculate platform fee from first account's settings
        List<SplitRuleResult> ruleResults = new ArrayList<>();
        for (SplitRuleCommand ruleCmd : command.rules()) {
            // SEC-BATCH-1: each referenced connected account must belong to the caller tenant, else a
            // tenant could build a split crediting foreign accounts. 404 on absent OR wrong-tenant.
            ConnectedAccount account = TenantOwnership.require(
                    repository.findAccountById(ruleCmd.connectedAccountId(), command.tenantId()),
                    "Connected account");

            // Calculate platform fee from first account (platform fee is tenant-level)
            if (platformFeeAmount == 0 && account.getPlatformFeePercent() != null) {
                platformFeeAmount = PlatformFee.calculateFee(
                        command.totalAmount(), account.getPlatformFeePercent(),
                        account.getPlatformFeeFixed());
            }

            SplitRule rule = SplitRule.create(
                    splitPayment.getId(), ruleCmd.connectedAccountId(),
                    ruleCmd.splitType(), ruleCmd.amount(), ruleCmd.percentage(),
                    command.currency());
            splitPayment.addRule(rule);
        }

        // SEC-28 (money guard): the platform fee must not meet or exceed the payment total, or the
        // distributable amount (and every per-account leg) would go NEGATIVE — a non-sensical split that
        // would attempt to pay out more than was collected. Reject before allocating any leg.
        if (platformFeeAmount >= command.totalAmount()) {
            throw new IllegalArgumentException(
                    "Platform fee (" + platformFeeAmount + ") must be less than the payment total ("
                            + command.totalAmount() + ")");
        }

        // Resolve calculated amounts based on total (minus platform fee)
        long distributableAmount = command.totalAmount() - platformFeeAmount;
        splitPayment.setTotalAmount(distributableAmount);
        splitPayment.resolveAmounts();
        splitPayment.setTotalAmount(command.totalAmount()); // Restore original

        // Persist rules
        for (SplitRule rule : splitPayment.getRules()) {
            rule = repository.saveSplitRule(rule);
            ruleResults.add(new SplitRuleResult(
                    rule.getId(), rule.getConnectedAccountId(),
                    rule.getSplitType(), rule.getCalculatedAmount(), rule.getCurrency()));
        }

        // Record platform fee if applicable
        if (platformFeeAmount > 0) {
            // SEC-BATCH-1: re-load the platform-fee account tenant-scoped (already validated in the
            // loop above, but keep the read tenant-isolated for defence in depth).
            ConnectedAccount firstAccount = repository.findAccountById(
                    command.rules().get(0).connectedAccountId(), command.tenantId()).orElse(null);
            PlatformFee fee = PlatformFee.create(
                    splitPayment.getId(), command.tenantId(), platformFeeAmount,
                    command.currency(),
                    firstAccount != null ? firstAccount.getPlatformFeePercent() : null,
                    firstAccount != null ? firstAccount.getPlatformFeeFixed() : 0);
            fee.setDescription("Platform fee for payment " + command.paymentId());
            repository.savePlatformFee(fee);
        }

        // GAP-063 (CARDINAL RULE): book the balanced split-distribution journal entry INSIDE this
        // REQUIRES_NEW transaction — same tx as the split row tree, NO try/catch — so a posting failure
        // rolls the whole split creation back. SplitPaymentService's outer catch is narrowed to the
        // (tenant, payment) DataIntegrityViolationException and re-throws everything else, so a ledger
        // failure propagates to the caller rather than being swallowed. Idempotency: keyed by
        // (splitPaymentId, "Split payment created") under V4028; the SEC-20 read-through + V4034 UNIQUE
        // guarantee one split per (tenant, payment), so one posting per business creation.
        List<LedgerPort.Leg> legs = splitPayment.getRules().stream()
                .map(r -> new LedgerPort.Leg(r.getConnectedAccountId(), r.getCalculatedAmount()))
                .toList();
        ledgerPort.postSplitDistribution(command.tenantId(), splitPayment.getId(), command.paymentId(),
                command.currency(), legs, platformFeeAmount, CallerMode.isLive());

        splitPayment.markProcessing();
        repository.saveSplitPayment(splitPayment);

        eventPublisher.publishEvent("SplitPayment", splitPayment.getId(), "SplitPaymentCreated",
                Map.of("paymentId", command.paymentId(),
                        "totalAmount", command.totalAmount(),
                        "currency", command.currency(),
                        "ruleCount", command.rules().size(),
                        "platformFee", platformFeeAmount,
                        "tenantId", command.tenantId()),
                command.tenantId());

        log.info("Split payment created: id={}, payment={}, rules={}, fee={}, tenant={}",
                splitPayment.getId(), command.paymentId(), command.rules().size(),
                platformFeeAmount, command.tenantId());

        return new SplitPaymentResult(
                splitPayment.getId(), command.paymentId(), splitPayment.getStatus(),
                command.totalAmount(), command.currency(), ruleResults, platformFeeAmount);
    }
}
