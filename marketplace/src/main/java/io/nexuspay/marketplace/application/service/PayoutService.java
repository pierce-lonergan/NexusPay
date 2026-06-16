package io.nexuspay.marketplace.application.service;

import io.nexuspay.common.tenant.TenantOwnership;
import io.nexuspay.marketplace.application.port.in.SchedulePayoutUseCase;
import io.nexuspay.marketplace.application.port.out.MarketplaceEventPublisher;
import io.nexuspay.marketplace.application.port.out.MarketplaceRepository;
import io.nexuspay.marketplace.application.port.out.PayoutExecutionPort;
import io.nexuspay.marketplace.domain.AccountState;
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

    // SEC-25: payouts.failure_reason is VARCHAR(256) (V4002:68). An oversized PSP failureReason or
    // exception message must be capped before markFailed/savePayout, or the terminal UPDATE throws a
    // value-too-long DataException and the row never cleanly reaches FAILED (same hazard the reconciler
    // guards). Cap matches the column width.
    private static final int FAILURE_REASON_MAX_LEN = 256;

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
        // SEC-BATCH-1: referenced-resource ownership — the connected account must belong to the
        // caller's tenant. Scoping the load to command.tenantId() (and 404-ing on mismatch) prevents
        // money misdirection where tenant B creates a payout crediting tenant A's account.
        ConnectedAccount account = TenantOwnership.require(
                repository.findAccountById(command.connectedAccountId(), command.tenantId()),
                "Connected account");

        // Only ACTIVE (KYC-verified) accounts may receive funds. Without this
        // gate, payouts could be created and later executed for ONBOARDING,
        // SUSPENDED, or CLOSED accounts.
        if (account.getStatus() != AccountState.ACTIVE) {
            throw new IllegalStateException(
                    "Connected account " + command.connectedAccountId() +
                    " is not eligible for payouts (status=" + account.getStatus() + ")");
        }

        if (command.amount() <= 0) {
            throw new IllegalArgumentException("Payout amount must be positive: " + command.amount());
        }

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
        // SEC-BATCH-1: scope by caller tenant (previously the tenant arg was ignored, leaking another
        // tenant's payouts for a guessed accountId).
        return repository.findPayoutsByAccountId(connectedAccountId, tenantId).stream()
                .map(this::toPayoutResult)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PayoutResult getPayout(String payoutId, String tenantId) {
        // SEC-BATCH-1: tenant-scoped by-id read — 404 on absent OR wrong-tenant (no existence oracle).
        Payout payout = TenantOwnership.require(
                repository.findPayoutById(payoutId, tenantId), "Payout");
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
            // SEC-11: atomically claim this payout BEFORE any disbursement. Only the winner of the
            // conditional UPDATE (PENDING -> PROCESSING, rows-affected==1) proceeds; a concurrent
            // replica/cycle that selected the same PENDING row gets false here and NEVER calls
            // payoutExecution.execute(...). This is the exactly-once-disbursement guarantee and holds
            // even if the scheduler lock ever fails open (the lock only reduces contention). The blind
            // markProcessing()+savePayout() it replaces was last-write-wins and did NOT arbitrate.
            if (!repository.claimPayoutForProcessing(payout.getId())) {
                log.debug("Payout {} already claimed by another replica/cycle; skipping", payout.getId());
                continue;
            }
            try {
                // Reflect the DB-side PENDING -> PROCESSING transition (done by the claim UPDATE) on the
                // in-memory aggregate before the terminal markPaid/markFailed write below.
                payout.markProcessing();

                var result = payoutExecution.execute(
                        new PayoutExecutionPort.PayoutExecutionRequest(
                                payout.getId(), payout.getConnectedAccountId(),
                                payout.getAmount(), payout.getCurrency(), payout.getMethod(),
                                // SEC-25: deterministic key the PayoutReconciler will REUSE byte-for-byte
                                // on every re-drive, so a crash between this claim+disburse and the
                                // terminal save is recovered without double-paying (PSP dedups the key).
                                Payout.idempotencyKey(payout.getId())));

                if (result.success()) {
                    payout.markPaid(result.externalReference());
                    eventPublisher.publishEvent("Payout", payout.getId(), "PayoutPaid",
                            Map.of("externalReference", result.externalReference(),
                                    "tenantId", payout.getTenantId()),
                            payout.getTenantId());
                    log.info("Payout executed: id={}, ref={}", payout.getId(), result.externalReference());
                } else {
                    payout.markFailed(truncateReason(result.failureReason()));
                    eventPublisher.publishEvent("Payout", payout.getId(), "PayoutFailed",
                            Map.of("reason", String.valueOf(result.failureReason()),
                                    "tenantId", payout.getTenantId()),
                            payout.getTenantId());
                    log.warn("Payout failed: id={}, reason={}", payout.getId(), result.failureReason());
                }

                repository.savePayout(payout);
            } catch (Exception e) {
                payout.markFailed(truncateReason(e.getMessage()));
                repository.savePayout(payout);
                log.error("Payout processing error: id={}, error={}", payout.getId(), e.getMessage(), e);
            }
        }

        if (!pendingPayouts.isEmpty()) {
            log.info("Processed {} pending payouts", pendingPayouts.size());
        }
    }

    /** Caps a failure reason to the failure_reason column width (256) so the terminal save can't throw. */
    private static String truncateReason(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= FAILURE_REASON_MAX_LEN ? reason : reason.substring(0, FAILURE_REASON_MAX_LEN);
    }

    private PayoutResult toPayoutResult(Payout p) {
        return new PayoutResult(
                p.getId(), p.getConnectedAccountId(), p.getAmount(), p.getCurrency(),
                p.getStatus(), p.getMethod(), p.getScheduledAt(), p.getPaidAt(),
                p.getFailureReason(), p.getExternalReference(), p.getCreatedAt());
    }
}
