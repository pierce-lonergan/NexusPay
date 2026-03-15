package io.nexuspay.fraud.adapter.out.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.fraud.config.FraudProperties;
import io.nexuspay.fraud.domain.model.FraudRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Hot-reloadable fraud rule cache backed by Valkey (Redis-compatible).
 *
 * <p>Rules are cached per tenant with a configurable TTL. Cache invalidation
 * is triggered on rule create/update/disable operations, ensuring rules
 * are available for evaluation within seconds of changes.</p>
 *
 * <p>Key pattern: {@code fraud:rules:{tenantId}}</p>
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Component
public class ValkeyFraudRuleCache {

    private static final Logger log = LoggerFactory.getLogger(ValkeyFraudRuleCache.class);
    private static final String KEY_PREFIX = "fraud:rules:";

    private final StringRedisTemplate redisTemplate;
    private final FraudProperties fraudProperties;
    private final ObjectMapper objectMapper;

    public ValkeyFraudRuleCache(StringRedisTemplate redisTemplate,
                                 FraudProperties fraudProperties,
                                 ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.fraudProperties = fraudProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Gets active rules from cache for a tenant.
     *
     * @return cached rules, or null if cache miss
     */
    public List<FraudRule> getActiveRules(String tenantId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + tenantId);
            if (json == null) return null;

            return objectMapper.readValue(json, new TypeReference<List<FraudRule>>() {});
        } catch (Exception e) {
            log.warn("Failed to read fraud rules from cache for tenant {}: {}", tenantId, e.getMessage());
            return null; // Fail open — fall through to DB
        }
    }

    /**
     * Caches active rules for a tenant with the configured TTL.
     */
    public void cacheRules(String tenantId, List<FraudRule> rules) {
        try {
            String json = objectMapper.writeValueAsString(rules);
            redisTemplate.opsForValue().set(
                    KEY_PREFIX + tenantId, json,
                    fraudProperties.getNativeRules().getCacheTtl()
            );
        } catch (Exception e) {
            log.warn("Failed to cache fraud rules for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    /**
     * Invalidates the cached rules for a tenant, forcing a reload on next access.
     */
    public void invalidate(String tenantId) {
        try {
            redisTemplate.delete(KEY_PREFIX + tenantId);
            log.debug("Invalidated fraud rule cache for tenant {}", tenantId);
        } catch (Exception e) {
            log.warn("Failed to invalidate fraud rule cache for tenant {}: {}", tenantId, e.getMessage());
        }
    }
}
