package io.nexuspay.payment.adapter.out.fx;

import io.nexuspay.payment.application.port.fx.FxRatePort;
import io.nexuspay.payment.domain.fx.FxRate;
import io.nexuspay.payment.domain.fx.FxRatePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Valkey-backed caching layer for FX rates.
 * Wraps the primary rate provider (ECB) with cache-aside pattern.
 * Serves stale rates when the upstream provider is unavailable.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Component
@Primary
public class ValkeyFxRateCache implements FxRatePort {

    private static final Logger LOG = LoggerFactory.getLogger(ValkeyFxRateCache.class);
    private static final String CACHE_PREFIX = "fx:rate:";
    private static final String PROVIDER_NAME = "ValkeyCache";

    private final FxRatePort delegate;
    private final StringRedisTemplate redisTemplate;
    private final Duration cacheTtl;
    private final Duration staleTtl;

    public ValkeyFxRateCache(
            @Qualifier("ecbFxRateAdapter") FxRatePort delegate,
            StringRedisTemplate redisTemplate,
            @Value("${nexuspay.fx.cache.ttl:PT1H}") Duration cacheTtl,
            @Value("${nexuspay.fx.cache.stale-ttl:PT24H}") Duration staleTtl) {
        this.delegate = delegate;
        this.redisTemplate = redisTemplate;
        this.cacheTtl = cacheTtl;
        this.staleTtl = staleTtl;
    }

    @Override
    public FxRate getRate(String fromCurrency, String toCurrency) {
        String cacheKey = cacheKey(fromCurrency, toCurrency);

        // Try cache first
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                FxRate rate = deserialize(cached, fromCurrency, toCurrency);
                if (rate != null && !rate.isStale(cacheTtl)) {
                    return rate;
                }
                // Rate is stale but within stale-ttl — try refresh, fall back to stale
            }
        } catch (Exception e) {
            LOG.warn("Valkey cache read failed for {}, falling through to provider: {}",
                    cacheKey, e.getMessage());
        }

        // Fetch from upstream provider
        try {
            FxRate freshRate = delegate.getRate(fromCurrency, toCurrency);
            cacheRate(cacheKey, freshRate);
            return freshRate;
        } catch (Exception e) {
            LOG.warn("Upstream FX provider failed for {}/{}: {}",
                    fromCurrency, toCurrency, e.getMessage());

            // Try serving stale cached rate
            try {
                String cached = redisTemplate.opsForValue().get(cacheKey);
                if (cached != null) {
                    FxRate staleRate = deserialize(cached, fromCurrency, toCurrency);
                    if (staleRate != null && !staleRate.isStale(staleTtl)) {
                        LOG.info("Serving stale FX rate for {}/{} (upstream unavailable)", fromCurrency, toCurrency);
                        return staleRate;
                    }
                }
            } catch (Exception cacheEx) {
                LOG.error("Both upstream and cache failed for {}/{}", fromCurrency, toCurrency);
            }
            throw new RuntimeException("FX rate unavailable for " + fromCurrency + "/" + toCurrency, e);
        }
    }

    @Override
    public List<FxRate> getAllRates(String baseCurrency) {
        // Delegate directly — bulk rate fetches are less latency-sensitive
        return delegate.getAllRates(baseCurrency);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME + "(" + delegate.providerName() + ")";
    }

    @Override
    public Instant lastUpdated() {
        return delegate.lastUpdated();
    }

    private void cacheRate(String key, FxRate rate) {
        try {
            String serialized = serialize(rate);
            redisTemplate.opsForValue().set(key, serialized, staleTtl);
        } catch (Exception e) {
            LOG.warn("Failed to cache FX rate {}: {}", key, e.getMessage());
        }
    }

    private String cacheKey(String from, String to) {
        return CACHE_PREFIX + from.toUpperCase() + ":" + to.toUpperCase();
    }

    private String serialize(FxRate rate) {
        return rate.rate().toPlainString() + "|" +
                rate.inverseRate().toPlainString() + "|" +
                rate.provider() + "|" +
                rate.timestamp().toEpochMilli();
    }

    private FxRate deserialize(String data, String from, String to) {
        try {
            String[] parts = data.split("\\|");
            if (parts.length != 4) return null;
            return new FxRate(
                    new FxRatePair(from, to),
                    new BigDecimal(parts[0]),
                    new BigDecimal(parts[1]),
                    parts[2],
                    Instant.ofEpochMilli(Long.parseLong(parts[3]))
            );
        } catch (Exception e) {
            LOG.warn("Failed to deserialize cached FX rate: {}", e.getMessage());
            return null;
        }
    }
}
