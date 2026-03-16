package io.nexuspay.payment.adapter.out.cache;

import io.nexuspay.payment.application.port.routing.PspHealthRepository;
import io.nexuspay.payment.domain.routing.PspHealthSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Valkey-backed PSP health tracking: auth rates, latency percentiles, circuit breaker state.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Repository
public class ValkeyPspHealthCache implements PspHealthRepository {

    private static final Logger LOG = LoggerFactory.getLogger(ValkeyPspHealthCache.class);
    private static final String PREFIX = "routing:health:";
    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;

    public ValkeyPspHealthCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<PspHealthSnapshot> getHealth(String pspConnector) {
        try {
            Map<Object, Object> data = redis.opsForHash().entries(key(pspConnector));
            if (data.isEmpty()) return Optional.empty();

            return Optional.of(new PspHealthSnapshot(
                    pspConnector,
                    parseDouble(data, "auth_rate", 0.95),
                    parseLong(data, "total_tx", 0),
                    parseDouble(data, "latency_p50", 100.0),
                    parseDouble(data, "latency_p95", 200.0),
                    parseDouble(data, "latency_p99", 500.0),
                    "true".equals(String.valueOf(data.getOrDefault("cb_open", "false"))),
                    Instant.now()
            ));
        } catch (Exception e) {
            LOG.warn("Failed to get health for {}: {}", pspConnector, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<PspHealthSnapshot> getAllHealth() {
        // In production, maintain a PSP set in Valkey; for now scan is acceptable
        List<PspHealthSnapshot> snapshots = new ArrayList<>();
        try {
            Set<String> keys = redis.keys(PREFIX + "*");
            if (keys != null) {
                for (String k : keys) {
                    String connector = k.replace(PREFIX, "");
                    getHealth(connector).ifPresent(snapshots::add);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to get all PSP health: {}", e.getMessage());
        }
        return snapshots;
    }

    @Override
    public void recordAuthResult(String pspConnector, boolean success, long latencyMs) {
        try {
            String k = key(pspConnector);
            redis.opsForHash().increment(k, "total_tx", 1);
            if (success) redis.opsForHash().increment(k, "success_tx", 1);

            // Recompute auth rate
            Object successObj = redis.opsForHash().get(k, "success_tx");
            Object totalObj = redis.opsForHash().get(k, "total_tx");
            if (successObj != null && totalObj != null) {
                long s = Long.parseLong(successObj.toString());
                long t = Long.parseLong(totalObj.toString());
                double rate = t > 0 ? (double) s / t : 0.95;
                redis.opsForHash().put(k, "auth_rate", String.valueOf(rate));
            }

            // Update latency (simple exponential moving average)
            Object currentP95 = redis.opsForHash().get(k, "latency_p95");
            double prevP95 = currentP95 != null ? Double.parseDouble(currentP95.toString()) : latencyMs;
            double newP95 = prevP95 * 0.95 + latencyMs * 0.05;
            redis.opsForHash().put(k, "latency_p95", String.valueOf(newP95));

            redis.expire(k, TTL);
        } catch (Exception e) {
            LOG.warn("Failed to record auth result for {}: {}", pspConnector, e.getMessage());
        }
    }

    @Override
    public void updateCircuitBreakerState(String pspConnector, boolean open) {
        try {
            redis.opsForHash().put(key(pspConnector), "cb_open", String.valueOf(open));
        } catch (Exception e) {
            LOG.warn("Failed to update circuit breaker for {}: {}", pspConnector, e.getMessage());
        }
    }

    private String key(String psp) {
        return PREFIX + psp;
    }

    private double parseDouble(Map<Object, Object> data, String field, double defaultVal) {
        Object val = data.get(field);
        if (val == null) return defaultVal;
        try { return Double.parseDouble(val.toString()); } catch (Exception e) { return defaultVal; }
    }

    private long parseLong(Map<Object, Object> data, String field, long defaultVal) {
        Object val = data.get(field);
        if (val == null) return defaultVal;
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return defaultVal; }
    }
}
