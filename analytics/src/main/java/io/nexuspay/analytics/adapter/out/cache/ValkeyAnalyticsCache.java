package io.nexuspay.analytics.adapter.out.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Valkey cache for analytics query results.
 * Cache-aside pattern with graceful degradation on failure.
 *
 * @since 0.3.0 (Sprint 3.6)
 */
@Component
public class ValkeyAnalyticsCache {

    private static final Logger LOG = LoggerFactory.getLogger(ValkeyAnalyticsCache.class);
    private static final String PREFIX = "analytics:";

    @Value("${nexuspay.analytics.cache.ttl:PT5M}")
    private Duration cacheTtl;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ValkeyAnalyticsCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Attempts to retrieve a cached result for the given endpoint and query hash.
     */
    public <T> Optional<T> get(String endpoint, String queryHash, Class<T> type) {
        try {
            String key = key(endpoint, queryHash);
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                LOG.debug("Cache hit for {}", key);
                return Optional.of(objectMapper.readValue(cached, type));
            }
        } catch (Exception e) {
            LOG.warn("Cache get failed for {}:{}: {}", endpoint, queryHash, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Stores a result in cache.
     */
    public <T> void put(String endpoint, String queryHash, T result) {
        try {
            String key = key(endpoint, queryHash);
            String json = objectMapper.writeValueAsString(result);
            redis.opsForValue().set(key, json, cacheTtl);
            LOG.debug("Cached result for {} (TTL={})", key, cacheTtl);
        } catch (Exception e) {
            LOG.warn("Cache put failed for {}:{}: {}", endpoint, queryHash, e.getMessage());
        }
    }

    /**
     * Creates a deterministic hash of query parameters for cache key generation.
     */
    public static String hashQuery(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                if (part != null) md.update(part.getBytes());
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (Exception e) {
            // Fallback to simple concatenation
            return String.join(":", parts).hashCode() + "";
        }
    }

    private String key(String endpoint, String queryHash) {
        return PREFIX + endpoint + ":" + queryHash;
    }
}
