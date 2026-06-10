package io.nexuspay.billing.adapter.in.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cross-instance lock for billing schedulers, so that with N application
 * replicas a daily/periodic job runs on exactly one instance per cycle.
 *
 * <p>Without this, every replica's {@code @Scheduled} cron fires concurrently and
 * the same subscription is invoiced and charged N times (B-001). Acquisition is
 * a Valkey {@code SET key NX EX} (atomic), like {@code OutboxRelay} (GAP-007).</p>
 *
 * <p><b>Fail-closed, deliberately.</b> If Valkey is unreachable the cycle is
 * SKIPPED, not run — the opposite of {@code OutboxRelay} (which fails open
 * because relaying twice is deduped downstream). A missed billing cycle is
 * harmless and self-heals next cycle (the work is due-based — the same
 * subscriptions are re-selected); running unguarded risks real double-charges.
 * See ADR-006.</p>
 *
 * <p><b>Lease renewal.</b> A billing run can legitimately take a long time (up to
 * 500 subscriptions × synchronous PSP charges). A fixed TTL shorter than the run
 * would expire mid-charge and let a second replica start — re-opening the
 * double-charge. While {@code work} runs we therefore renew the lease (PEXPIRE,
 * owner-checked) at {@code ttl/3}; if this instance dies, renewals stop and the
 * lease expires so another instance can recover. Both renew and release are
 * single-round-trip compare-and-act Lua scripts, so they can never touch a lease
 * another instance has since acquired.</p>
 */
@Component
public class SchedulerLock {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLock.class);
    private static final String LOCK_PREFIX = "billing:scheduler:lock:";

    // Owner-checked compare-and-act: only the holder may extend/release the lease.
    private static final RedisScript<Long> RENEW_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);
    private static final RedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final String instanceId = ProcessHandle.current().pid() + "@" + hostName();
    // Guards against same-JVM reentrancy if the task scheduler ever runs >1 thread.
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public SchedulerLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Runs {@code work} iff this instance wins the lock for {@code name}.
     *
     * @return true if the work ran on this instance; false if another instance
     *         (or thread) holds the lock, or the lock store was unavailable.
     */
    public boolean runExclusively(String name, Duration ttl, Runnable work) {
        if (!inFlight.add(name)) {
            log.debug("Scheduler job '{}' already running on this instance; skipping", name);
            return false;
        }
        try {
            return runLocked(name, ttl, work);
        } finally {
            inFlight.remove(name);
        }
    }

    private boolean runLocked(String name, Duration ttl, Runnable work) {
        String key = LOCK_PREFIX + name;
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(key, instanceId, ttl);
        } catch (Exception e) {
            // FAIL CLOSED: skip this cycle rather than risk concurrent double-billing.
            log.warn("Scheduler lock '{}' unavailable; skipping cycle (fail-closed): {}",
                    name, e.getMessage());
            return false;
        }
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("Scheduler lock '{}' held by another instance; skipping", name);
            return false;
        }

        ScheduledExecutorService heartbeat = startHeartbeat(name, key, ttl);
        try {
            work.run();
            return true;
        } finally {
            heartbeat.shutdownNow();
            release(key, name);
        }
    }

    private ScheduledExecutorService startHeartbeat(String name, String key, Duration ttl) {
        long periodMs = Math.max(ttl.toMillis() / 3, 100);
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "billing-lock-renew-" + name);
            t.setDaemon(true);
            return t;
        });
        exec.scheduleAtFixedRate(() -> renew(key, name, ttl), periodMs, periodMs, TimeUnit.MILLISECONDS);
        return exec;
    }

    private void renew(String key, String name, Duration ttl) {
        try {
            Long ok = redis.execute(RENEW_IF_OWNER, List.of(key), instanceId, Long.toString(ttl.toMillis()));
            if (!Long.valueOf(1L).equals(ok)) {
                log.warn("Scheduler lease '{}' lost before renewal (concurrent takeover or expiry)", name);
            }
        } catch (Exception e) {
            log.debug("Scheduler lease '{}' renewal failed (will retry next tick): {}", name, e.getMessage());
        }
    }

    /** Atomic owner-checked release — cannot delete a lease another instance now holds. */
    private void release(String key, String name) {
        try {
            redis.execute(RELEASE_IF_OWNER, List.of(key), instanceId);
        } catch (Exception e) {
            log.debug("Could not release scheduler lock '{}' (TTL will reclaim): {}", name, e.getMessage());
        }
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
