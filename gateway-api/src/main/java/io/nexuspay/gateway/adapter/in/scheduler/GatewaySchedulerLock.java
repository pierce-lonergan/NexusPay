package io.nexuspay.gateway.adapter.in.scheduler;

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
 * Cross-instance scheduler lock for gateway-api, byte-for-byte the same fail-CLOSED idiom as
 * billing's {@code SchedulerLock} (atomic {@code SET key NX EX}, owner-checked RENEW/RELEASE Lua,
 * ttl/3 lease renewal), inlined here rather than taken as a {@code :billing} dependency.
 *
 * <p><b>Why inlined, not reused (B-022 / RFC openRisk #1).</b> {@code SchedulerLock} lives in
 * {@code :billing}; gateway-api does NOT (and architecturally should not) depend on {@code :billing}.
 * Lifting it to {@code :common} would force {@code spring-data-redis} + spring-context onto the
 * foundational {@code :common} module (which today has no Spring core dependency) and would touch a
 * billing-critical class. The RFC's sanctioned fallback is to inline the identical fail-closed
 * setIfAbsent + RELEASE_IF_OWNER idiom — that is this class. gateway-api already ships
 * {@code spring-boot-starter-data-redis} (build.gradle.kts:25), so {@code StringRedisTemplate} is on
 * the classpath.</p>
 *
 * <p><b>Fail-closed, deliberately (ADR-006).</b> If Valkey is unreachable the cycle is SKIPPED, not
 * run. A re-driven refund moves money; running unguarded on every replica when the lock store is down
 * is exactly the fail-OPEN behavior the B-022 NEVER-list forbids (that is {@code OutboxRelay}'s policy,
 * acceptable only because Kafka consumers dedup — wrong policy for money). A skipped reconcile cycle
 * self-heals next cycle: the same stuck rows are re-selected.</p>
 *
 * <p><b>Defense in depth, not the money backstop.</b> Even if this lock somehow let two replicas run,
 * no double-pay is possible: every re-drive submits the identical {@code refund-approval-<id>}
 * idempotency key and HyperSwitch collapses them to one refund. The lock just avoids redundant deduped
 * POSTs.</p>
 */
@Component
class GatewaySchedulerLock {

    private static final Logger log = LoggerFactory.getLogger(GatewaySchedulerLock.class);
    private static final String LOCK_PREFIX = "gateway:scheduler:lock:";

    // Owner-checked compare-and-act: only the holder may extend/release the lease, so a release/renew
    // can never touch a lease another replica has since acquired.
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

    GatewaySchedulerLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Runs {@code work} iff this instance wins the lock for {@code name}.
     *
     * @return true if the work ran on this instance; false if another instance (or thread) holds the
     *         lock, or the lock store was unavailable (fail-closed).
     */
    boolean runExclusively(String name, Duration ttl, Runnable work) {
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
            // FAIL CLOSED: skip this cycle rather than risk an unguarded multi-replica re-drive.
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
            Thread t = new Thread(r, "gateway-lock-renew-" + name);
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
