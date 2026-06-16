package io.nexuspay.gateway.adapter.out.webhook;

import io.nexuspay.common.rls.TenantWorkRunner;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookDeliveryRepository;
import io.nexuspay.gateway.adapter.out.persistence.JpaWebhookEndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * INT-4 (test H): the retrier is leader-locked exactly like {@code OutboxRelay} (B-018) — only the lock
 * holder runs the due-scan, and {@code @PreDestroy} releases the lock atomically (owner-checked
 * {@code RELEASE_IF_OWNER}) so it never deletes a lock another instance holds.
 *
 * <p>Two retriers share one in-memory Redis simulation (a {@code ConcurrentHashMap}-backed mock). The
 * loser's {@code findDueForRetry} must never run; only the winner's does.</p>
 */
class WebhookDeliveryRetrierLeaderTest {

    private static final String LEADER_LOCK_KEY = "webhook:retry:leader";

    private final ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
    private StringRedisTemplate redis;
    private final AtomicReference<Object> releaseScriptArgs = new AtomicReference<>();

    private JpaWebhookDeliveryRepository deliveryA;
    private JpaWebhookDeliveryRepository deliveryB;
    private JpaWebhookEndpointRepository endpoints;
    private TenantWorkRunner tenantWork;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        // setIfAbsent = SET key value NX: succeeds only if absent.
        when(ops.setIfAbsent(eq(LEADER_LOCK_KEY), any(String.class), any(Duration.class)))
                .thenAnswer(inv -> store.putIfAbsent(LEADER_LOCK_KEY, inv.getArgument(1)) == null);
        when(ops.get(LEADER_LOCK_KEY)).thenAnswer(inv -> store.get(LEADER_LOCK_KEY));

        // RELEASE_IF_OWNER Lua: delete only if the caller is the current holder. execute(script, keys,
        // args...) is varargs; Mockito may flatten the trailing instanceId() into the args list OR pass it as
        // a single Object[] — handle both (L-042 varargs footgun) by taking the last scalar element.
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(inv -> {
            Object last = inv.getArguments()[inv.getArguments().length - 1];
            String caller = last instanceof Object[] arr ? (String) arr[arr.length - 1] : (String) last;
            store.compute(LEADER_LOCK_KEY, (k, v) -> caller.equals(v) ? null : v);
            return 1L;
        });

        deliveryA = mock(JpaWebhookDeliveryRepository.class);
        deliveryB = mock(JpaWebhookDeliveryRepository.class);
        endpoints = mock(JpaWebhookEndpointRepository.class);
        tenantWork = mock(TenantWorkRunner.class);

        when(deliveryA.findDueForRetry(any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());
        when(deliveryB.findDueForRetry(any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());
    }

    /**
     * Two same-JVM instances share a pid, so the real {@code instanceId()} can't tell them apart — override
     * it per logical replica to simulate two distinct nodes (production never overrides it).
     */
    private WebhookDeliveryRetrier retrier(JpaWebhookDeliveryRepository deliveries, String instanceId) {
        WebhookDeliveryService svc = mock(WebhookDeliveryService.class);
        return new WebhookDeliveryRetrier(deliveries, endpoints, svc, redis, tenantWork, false) {
            @Override
            String instanceId() {
                return instanceId;
            }
        };
    }

    @Test
    void onlyTheLockHolderRunsTheDueScan() {
        WebhookDeliveryRetrier a = retrier(deliveryA, "instance-A");
        WebhookDeliveryRetrier b = retrier(deliveryB, "instance-B");

        a.retryDue();   // A acquires the lock and scans
        b.retryDue();   // B is locked out and must NOT scan

        verify(deliveryA, times(1)).findDueForRetry(any(Instant.class), any(Instant.class), any(Pageable.class));
        verify(deliveryB, never()).findDueForRetry(any(Instant.class), any(Instant.class), any(Pageable.class));
    }

    @Test
    void shutdownReleasesOwnLock_butNotAnotherInstancesLock() {
        WebhookDeliveryRetrier a = retrier(deliveryA, "instance-A");
        WebhookDeliveryRetrier b = retrier(deliveryB, "instance-B");

        a.retryDue();                                   // A holds the lock
        assertThat(store).containsKey(LEADER_LOCK_KEY);
        String holderBeforeB = store.get(LEADER_LOCK_KEY);

        // B shutting down must NOT delete A's lock (owner-checked release).
        b.shutdown();
        assertThat(store.get(LEADER_LOCK_KEY))
                .as("B must not release a lock owned by A")
                .isEqualTo(holderBeforeB);

        // A shutting down DOES release its own lock.
        a.shutdown();
        assertThat(store).as("A releases its own lock").doesNotContainKey(LEADER_LOCK_KEY);
    }
}
