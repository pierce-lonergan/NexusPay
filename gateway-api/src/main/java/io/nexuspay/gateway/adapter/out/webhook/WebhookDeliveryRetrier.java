package io.nexuspay.gateway.adapter.out.webhook;

import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookDeliveryRepository;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import io.nexuspay.gateway.adapter.out.persistence.WebhookDeliveryEntity;
import io.nexuspay.gateway.adapter.out.persistence.WebhookEndpointEntity;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * INT-4: leader-locked retrier for FAILED outbound webhook deliveries.
 *
 * <p>Polls {@code webhook_deliveries} every 5s for FAILED rows whose {@code next_attempt_at} is due and
 * re-drives each through the SAME shared {@link WebhookDeliveryService#send} path — so a retry is EXACTLY
 * as SSRF-safe (IP-pin, redirects disabled, timeouts) and canonical (byte-identical body, HMAC over those
 * bytes) as a first attempt, and signs with the endpoint's CURRENT secret (re-loaded per attempt, so a
 * rotation takes effect on the next attempt). The outcome is applied via the shared
 * {@link WebhookDeliveryService#recordOutcome} state machine (backoff or DEAD) — NO retry logic is
 * duplicated here.</p>
 *
 * <p><b>Leader election + graceful shutdown (B-018):</b> mirrors {@code OutboxRelay} byte-for-byte —
 * {@code setIfAbsent}+TTL with same-instance renew, atomic owner-checked {@code RELEASE_IF_OWNER} Lua,
 * {@code @Scheduled(fixedDelay)}, a {@code @PreDestroy} drain that waits for the in-flight cycle then
 * releases the lock. <b>Fail-OPEN</b> on Valkey down (single-replica is fine): unlike money re-drives,
 * a webhook retry is at-least-once and the {@code (endpoint_id, event_id)} uniqueness + the DELIVERED
 * terminal state make a duplicate attempt harmless, so the OutboxRelay policy (not the money-safe
 * GatewaySchedulerLock fail-closed policy) is the correct one here.</p>
 */
@Component
@ConditionalOnProperty(name = "nexuspay.webhook.retry.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookDeliveryRetrier {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryRetrier.class);

    private static final String LEADER_LOCK_KEY = "webhook:retry:leader";
    private static final Duration LEADER_LOCK_TTL = Duration.ofSeconds(5);

    // INT-4 BLOCKER (crash-recovery sweep): a PENDING row is swept ONLY once it is older than this — long
    // enough that a normal first attempt (record -> send -> recordOutcome, all on the consumer thread, send
    // bounded by 3s connect + 10s read in WebhookDeliveryService) has provably finished or crashed, so a row
    // that is merely mid-first-attempt is never double-driven. A pre-outcome crash leaves the PENDING row to be
    // recovered after this delay rather than lost forever.
    static final Duration PENDING_STALE_AFTER = Duration.ofMinutes(2);

    // INT-4 SHOULD_FIX (per-row claim lease): when this leader claims a row it pushes next_attempt_at out by
    // this lease so a SECOND leader (after a mid-batch lock expiry) cannot also pick it up while the first send
    // is in flight. The lease comfortably exceeds one send's worst case (3s connect + 10s read) yet is short
    // enough that a crash mid-send re-arms the row promptly. recordOutcome overwrites next_attempt_at with the
    // real backoff (or terminal null) as soon as the attempt completes, so the lease only governs the in-flight
    // window.
    static final Duration CLAIM_LEASE = Duration.ofSeconds(30);

    // Atomic owner-checked release — cannot delete a lock another instance has since acquired (avoids the
    // GET-then-DELETE TOCTOU). Mirrors OutboxRelay / billing SchedulerLock (B-018).
    private static final RedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    // Bounded batch per cycle (mirrors findUnpublishedEvents' LIMIT 100 idiom).
    private static final int BATCH = 50;

    private final JpaWebhookDeliveryRepository deliveryRepository;
    private final JpaWebhookEndpointRepository endpointRepository;
    private final WebhookDeliveryService delivery;          // the SHARED send + recordOutcome (no dup logic)
    private final StringRedisTemplate redisTemplate;
    private final TenantWorkRunner tenantWork;
    private final boolean rlsEnforced;

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean retrying = new AtomicBoolean(false);

    // Single injectable constructor -> no L-054 ambiguity (no second/seam ctor).
    public WebhookDeliveryRetrier(JpaWebhookDeliveryRepository deliveryRepository,
                                  JpaWebhookEndpointRepository endpointRepository,
                                  WebhookDeliveryService delivery,
                                  StringRedisTemplate redisTemplate,
                                  TenantWorkRunner tenantWork,
                                  @Value("${nexuspay.multi-tenancy.rls.enforce:false}") boolean rlsEnforced) {
        this.deliveryRepository = deliveryRepository;
        this.endpointRepository = endpointRepository;
        this.delivery = delivery;
        this.redisTemplate = redisTemplate;
        this.tenantWork = tenantWork;
        this.rlsEnforced = rlsEnforced;
    }

    @Scheduled(fixedDelay = 5000)
    public void retryDue() {
        if (shuttingDown.get()) return;
        if (!acquireLeaderLock()) return;       // only one replica retries
        retrying.set(true);
        try {
            Instant now = Instant.now();
            Instant staleBefore = now.minus(PENDING_STALE_AFTER);
            // BLOCKER: sweep FAILED-due AND stale PENDING (pre-outcome crash recovery) in one scan.
            List<WebhookDeliveryEntity> due =
                    deliveryRepository.findDueForRetry(now, staleBefore, PageRequest.of(0, BATCH));
            if (due.isEmpty()) return;
            log.debug("Retrying {} due webhook deliveries", due.size());
            for (WebhookDeliveryEntity d : due) {
                if (shuttingDown.get()) break;
                processOne(d);                  // TENANT-SCOPED per row, atomically claimed first
            }
        } finally {
            retrying.set(false);
        }
    }

    /**
     * Re-drives ONE due/stale delivery in its own tenant scope. First ATOMICALLY CLAIMS the row (a conditional
     * UPDATE that pushes next_attempt_at out by a short lease, conditioned on the row still being in the exact
     * due/stale state the scan saw) so a second leader that loaded the same row after a mid-batch lock expiry
     * cannot double-SEND it: exactly one leader's claim affects 1 row, the loser's affects 0 and skips. Only
     * after winning the claim does it re-load the endpoint for the CURRENT secret/URL, send through the shared
     * path, and apply the outcome via the shared state machine. A deleted/disabled endpoint stops the retries
     * (PermanentFailure -> DEAD) rather than looping forever.
     */
    void processOne(WebhookDeliveryEntity d) {
        Runnable work = () -> {
            Instant now = Instant.now();
            Instant staleBefore = now.minus(PENDING_STALE_AFTER);
            Instant claimUntil = now.plus(CLAIM_LEASE);
            // SHOULD_FIX (no double-send): atomic per-row claim. 0 rows updated => another leader already
            // claimed/advanced this row in the window between the scan and now -> skip (don't re-send).
            int claimed = deliveryRepository.claimForRetry(d.getId(), now, staleBefore, claimUntil);
            if (claimed == 0) {
                log.debug("Skipping delivery {} — not claimable (already claimed/advanced by another leader)",
                        d.getId());
                return;
            }
            // Re-load the freshly-claimed row: claimForRetry is @Modifying(clearAutomatically=true), so the
            // scan-loaded instance is now stale (it may still read PENDING/old next_attempt_at). Operate on the
            // managed, post-claim entity so recordOutcome's save writes the correct terminal/backoff state.
            WebhookDeliveryEntity row = deliveryRepository.findById(d.getId()).orElse(null);
            if (row == null) {
                log.warn("Claimed delivery {} disappeared before send", d.getId());
                return;
            }
            WebhookEndpointEntity ep =
                    endpointRepository.findByIdAndTenantId(row.getEndpointId(), row.getTenantId()).orElse(null);
            if (ep == null || !ep.isEnabled()) {
                delivery.recordOutcome(row,
                        new WebhookDeliveryService.SendOutcome.PermanentFailure(null, "endpoint absent/disabled"));
                return;
            }
            // SAME secure + canonical + signed send as a first attempt; CURRENT secret read inside send.
            WebhookDeliveryService.SendOutcome outcome =
                    delivery.send(ep, row.getCanonicalBody(), row.getEventType());
            delivery.recordOutcome(row, outcome);   // backoff / DEAD transition (shared helper)
        };
        try {
            if (rlsEnforced) {
                tenantWork.runInTenant(d.getTenantId(), work);   // RLS-bound, per-tenant tx
            } else {
                work.run();
            }
        } catch (Exception e) {
            log.warn("Retry failed for delivery {} (endpoint {}): {}",
                    d.getId(), d.getEndpointId(), e.getMessage());
        }
    }

    private boolean acquireLeaderLock() {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(LEADER_LOCK_KEY, instanceId(), LEADER_LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) return true;

            // Already hold it? renew.
            String holder = redisTemplate.opsForValue().get(LEADER_LOCK_KEY);
            if (instanceId().equals(holder)) {
                redisTemplate.expire(LEADER_LOCK_KEY, LEADER_LOCK_TTL);
                return true;
            }
            return false;
        } catch (Exception e) {
            // Fail open: if Valkey is down, proceed (single instance is fine; duplicate attempts are harmless
            // because of the (endpoint,event) uniqueness + DELIVERED terminal state). Mirrors OutboxRelay.
            log.debug("Webhook retry leader lock unavailable, proceeding: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Per-instance identity for the leader lock (pid@host, as OutboxRelay). Package-private + overridable so
     * a same-JVM leader test can simulate two DISTINCT replicas (two same-JVM instances would otherwise share
     * a pid and be indistinguishable); production never overrides it.
     */
    String instanceId() {
        return ProcessHandle.current().pid() + "@" + getHostName();
    }

    private static String getHostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Webhook retrier shutting down, waiting for in-flight cycle...");
        shuttingDown.set(true);
        // Wait up to 5 seconds for the current cycle to complete.
        for (int i = 0; i < 50 && retrying.get(); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Atomic owner-checked release — never delete a lock another instance acquired after our TTL elapsed.
        try {
            redisTemplate.execute(RELEASE_IF_OWNER, List.of(LEADER_LOCK_KEY), instanceId());
        } catch (Exception e) {
            log.debug("Could not release webhook retry leader lock: {}", e.getMessage());
        }
        log.info("Webhook retrier shutdown complete");
    }
}
