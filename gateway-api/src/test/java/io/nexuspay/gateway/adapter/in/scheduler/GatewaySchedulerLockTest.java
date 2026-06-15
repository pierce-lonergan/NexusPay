package io.nexuspay.gateway.adapter.in.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-022: the gateway scheduler lock must run a refund-reconcile cycle on exactly one replica, FAIL
 * CLOSED when Valkey is down (money safety — ADR-006; the NEVER-list forbids a fail-open unguarded
 * re-drive), guard same-instance reentrancy, and release its lease with an owner-checked atomic script.
 *
 * <p>A long TTL (5m) keeps the renewal heartbeat (ttl/3) from firing inside these millisecond tests,
 * so they stay timing-free.</p>
 */
class GatewaySchedulerLockTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private GatewaySchedulerLock lock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        lock = new GatewaySchedulerLock(redis);
    }

    @Test
    void runsWorkWhenLockAcquired() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        AtomicInteger ran = new AtomicInteger();

        boolean result = lock.runExclusively("refund-reconcile", Duration.ofMinutes(5), ran::incrementAndGet);

        assertThat(result).isTrue();
        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    void skipsWorkWhenLockHeldByAnotherInstance() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        AtomicInteger ran = new AtomicInteger();

        boolean result = lock.runExclusively("refund-reconcile", Duration.ofMinutes(5), ran::incrementAndGet);

        assertThat(result).isFalse();
        assertThat(ran.get()).as("work must NOT run when another instance holds the lock").isZero();
    }

    @Test
    void failsClosedWhenLockStoreUnavailable() {
        // Valkey down — a money re-drive cycle must be SKIPPED, never run unguarded on every replica.
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("connection refused"));
        AtomicInteger ran = new AtomicInteger();

        boolean result = lock.runExclusively("refund-reconcile", Duration.ofMinutes(5), ran::incrementAndGet);

        assertThat(result).as("fail-closed").isFalse();
        assertThat(ran.get()).as("work must NOT run if the lock store is unreachable").isZero();
    }

    @Test
    void releasesLeaseWithOwnerCheckedScript() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        lock.runExclusively("refund-reconcile", Duration.ofMinutes(5), () -> { });

        // The only execute() in a fast run is the release (the ttl/3 heartbeat never fires at 5m TTL).
        verify(redis).execute(any(RedisScript.class),
                eq(List.of("gateway:scheduler:lock:refund-reconcile")), any());
    }

    @Test
    void reentrantSameInstanceCallIsSkipped() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        AtomicBoolean innerRan = new AtomicBoolean(false);
        AtomicBoolean innerResult = new AtomicBoolean(true);

        lock.runExclusively("refund-reconcile", Duration.ofMinutes(5), () ->
                innerResult.set(lock.runExclusively("refund-reconcile", Duration.ofMinutes(5),
                        () -> innerRan.set(true))));

        assertThat(innerResult.get()).as("reentrant same-name call must be refused").isFalse();
        assertThat(innerRan.get()).as("reentrant work must not run").isFalse();
    }

    @Test
    void releaseErrorDoesNotMaskSuccessfulWork() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), any(List.class), any()))
                .thenThrow(new RuntimeException("valkey blip during release"));
        AtomicInteger ran = new AtomicInteger();

        boolean result = lock.runExclusively("refund-reconcile", Duration.ofMinutes(5), ran::incrementAndGet);

        assertThat(result).isTrue();
        assertThat(ran.get()).isEqualTo(1);
    }
}
