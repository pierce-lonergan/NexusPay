package io.nexuspay.marketplace.application.service;

import io.nexuspay.marketplace.application.port.in.SchedulePayoutUseCase;
import io.nexuspay.marketplace.application.port.out.MarketplaceEventPublisher;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort;
import io.nexuspay.marketplace.domain.ConnectedAccount;
import io.nexuspay.marketplace.domain.Payout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Service for creating, scheduling, and executing payouts to connected accounts.
 * Enforces minimum payout thresholds and coordinates with the payout execution port.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
@Service
public class PayoutService implements SchedulePayoutUseCase {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    private final MarketplaceRepository repository;
    private final PayoutExecutionPort payoutExecution;
    private final MarketplaceEventPublisher eventPublisher;

    public PayoutService(MarketplaceRepository repository,
                          PayoutExecutionPort payoutExecution,
                          MarketplaceEventPublisher eventPublisher) {
        this.repository = repository;
        this.payoutExecution = payoutExecution;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public PayoutResult createPayout(CreatePayoutCommand command) {
        // Validate connected account and enforce minimum threshold
        ConnectedAccount account = repository.findAccountById(command.connectedAccountId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Connected account not found: " + command.connectedAccountId()));

        if (command.amount() < account.getPayoutMinimum()) {
            throw new IllegalArgumentException(
                    "Payout amount " + command.amount() + " is below minimum threshold " +
                    account.getPayoutMinimum() + " for account " + command.connectedAccountId());
        }

        Payout payout = Payout.create(
                command.connectedAccountId(), command.tenantId(),
                command.amount(), command.currency(), command.method());

        if (command.scheduledAt() != null) {
            payout.schedule(command.scheduledAt());
        }

        payout = repository.savePayout(payout);

        eventPublisher.publishEvent("Payout", payout.getId(), "PayoutCreated",
                Map.of("connectedAccountId", command.connectedAccountId(),
                        "amount", command.amount(),
                        "currency", command.currency(),
                        "method", command.method().name(),
                        "tenantId", command.tenantId()),
                command.tenantId());

        log.info("Payout created: id={}, account={}, amount={}{}, tenant={}",
                payout.getId(), command.connectedAccountId(),
                command.amount(), command.currency(), command.tenantId());

        return toPayoutResult(payout);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayoutResult> listPayouts(String tenantId, String connectedAccountId) {
        return repository.findPayoutsByAccountId(connectedAccountId).stream()
                .map(this::toPayoutResult)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PayoutResult getPayout(String payoutId, String tenantId) {
        Payout payout = repository.findPayoutById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("Payout not found: " + payoutId));
        return toPayoutResult(payout);
    }

    /**
     * Processes all pending payouts that are due for execution.
     * Called by the PayoutScheduler on a cron schedule.
     */
    @Transactional
    public void processPendingPayouts() {
        List<Payout> pendingPayouts = repository.findPendingPayoutsDueBefore(Instant.now());

        for (Payout payout : pendingPayouts) {
            try {
                payout.markProcessing();
                repository.savePayout(payout);

                var result = payoutExecution.execute(
                        new PayoutExecutionPort.PayoutExecutionRequest(
                                payout.getId(), payout.getConnectedAccountId(),
                                payout.getAmount(), payout.getCurrency(), payout.getMethod()));

                if (result.success()) {
                    payout.markPaid(result.externalReference());
                    eventPublisher.publishEvent("Payout", payout.getId(), "PayoutPaid",
                            Map.of("externalReference", result.externalReference(),
                                    "tenantId", payout.getTenantId()),
                            payout.getTenantId());
                    log.info("Payout executed: id={}, ref={}", payout.getId(), result.externalReference());
                } else {
                    payout.markFailed(result.failureReason());
                    eventPublisher.publishEvent("Payout", payout.getId(), "PayoutFailed",
                            Map.of("reason", result.failureReason(),
                                    "tenantId", payout.getTenantId()),
                            payout.getTenantId());
                    log.warn("Payout failed: id={}, reason={}", payout.getId(), result.failureReason());
                }

                repository.savePayout(payout);
            } catch (Exception e) {
                payout.markFailed(e.getMessage());
                repository.savePayout(payout);
                log.error("Payout processing error: id={}, error={}", payout.getId(), e.getMessage(), e);
            }
        }

        if (!pendingPayouts.isEmpty()) {
            log.info("Processed {} pending payouts", pendingPayouts.size());
        }
    }

    private PayoutResult toPayoutResult(Payout p) {
        return new PayoutResult(
                p.getId(), p.getConnectedAccountId(), p.getAmount(), p.getCurrency(),
                p.getStatus(), p.getMethod(), p.getScheduledAt(), p.getPaidAt(),
                p.getFailureReason(), p.getExternalReference(), p.getCreatedAt());
    }
}
