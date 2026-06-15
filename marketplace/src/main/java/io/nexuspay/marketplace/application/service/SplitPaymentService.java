package io.nexuspay.marketplace.application.service;

import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.marketplace.application.port.in.CreateSplitPaymentUseCase;
import io.nexuspay.marketplace.application.port.out.MarketplaceEventPublisher;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final MarketplaceEventPublisher eventPublisher;

    public SplitPaymentService(MarketplaceRepository repository,
                                MarketplaceEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public SplitPaymentResult createSplitPayment(CreateSplitCommand command) {
        validateSplitRules(command.rules());

        // Look up the platform account to determine fee structure
        long platformFeeAmount = 0;

        // Create split payment
        SplitPayment splitPayment = SplitPayment.create(
                command.paymentId(), command.tenantId(),
                command.totalAmount(), command.currency());
        splitPayment = repository.saveSplitPayment(splitPayment);

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

    @Override
    @Transactional(readOnly = true)
    public SplitPaymentResult getSplitPayment(String splitPaymentId, String tenantId) {
        // SEC-BATCH-1: tenant-scoped by-id read — 404 on absent OR wrong-tenant.
        SplitPayment sp = TenantOwnership.require(
                repository.findSplitPaymentById(splitPaymentId, tenantId), "Split payment");

        List<SplitRuleResult> ruleResults = sp.getRules().stream()
                .map(r -> new SplitRuleResult(
                        r.getId(), r.getConnectedAccountId(),
                        r.getSplitType(), r.getCalculatedAmount(), r.getCurrency()))
                .toList();

        long feeAmount = repository.findFeesBySplitPaymentId(splitPaymentId)
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
