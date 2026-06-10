package io.nexuspay.billing.adapter.in.scheduler;

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
 * B-001: the scheduler lock ensures only one replica runs a billing cycle, FAILS
 * CLOSED when the lock store is down (money safety), guards same-instance
 * reentrancy, and releases its lease with an owner-checked atomic script.
 *
 * <p>A long TTL (1h) is used so the renewal heartbeat (ttl/3) never fires inside
 * these millisecond tests — keeping them timing-free (§17.5). Lease-renewal
 * timing is integration-level and out of scope for unit tests.</p>
 */
class SchedulerLockTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SchedulerLock lock;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        lock = new SchedulerLock(redis);
    }

    @Test
    void runsWorkWhenLockAcquired() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        AtomicInteger ran = new AtomicInteger();

        boolean result = lock.runExclusively("renewals", Duration.ofHours(1), ran::incrementAndGet);

        assertThat(result).isTrue();
        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    void skipsWorkWhenLockHeldByAnotherInstance() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        AtomicInteger ran = new AtomicInteger();

        boolean result = lock.runExclusively("renewals", Duration.ofHours(1), ran::incrementAndGet);

        assertThat(result).isFalse();
        assertThat(ran.get()).as("work must NOT run when another instance holds the lock").isZero();
    }

    @Test
    void failsClosedWhenLockStoreUnavailable() {
        // Valkey down — a money cycle must be SKIPPED, never run unguarded.
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("connection refused"));
        AtomicInteger ran = new AtomicInteger();

        boolean result = lock.runExclusively("dunning", Duration.ofHours(1), ran::incrementAndGet);

        assertThat(result).as("fail-closed").isFalse();
        assertThat(ran.get()).as("work must NOT run if the lock store is unreachable").isZero();
    }

    @Test
    void releasesLeaseWithOwnerCheckedScript() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        lock.runExclusively("renewals", Duration.ofHours(1), () -> { });

        // The only execute() in a fast run is the release (the ttl/3 heartbeat
        // never fires at a 1h TTL). It must be the owner-checked Lua on this key.
        verify(redis).execute(any(RedisScript.class),
                eq(List.of("billing:scheduler:lock:renewals")), any());
    }

    @Test
    void reentrantSameInstanceCallIsSkipped() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        AtomicBoolean innerRan = new AtomicBoolean(false);
        AtomicBoolean innerResult = new AtomicBoolean(true);

        lock.runExclusively("renewals", Duration.ofHours(1), () ->
                innerResult.set(lock.runExclusively("renewals", Duration.ofHours(1),
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

        boolean result = lock.runExclusively("renewals", Duration.ofHours(1), ran::incrementAndGet);

        assertThat(result).isTrue();
        assertThat(ran.get()).isEqualTo(1);
    }
}
