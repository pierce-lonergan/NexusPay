package io.nexuspay.payment.adapter.out.cache;

import io.nexuspay.payment.application.port.routing.AuthRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * Valkey-backed auth rate tracking with 7-day sliding window.
 * Stores success/total counters per PSP, optionally segmented by card attributes.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Repository
public class ValkeyAuthRateCache implements AuthRateRepository {

    private static final Logger LOG = LoggerFactory.getLogger(ValkeyAuthRateCache.class);
    private static final String PREFIX = "routing:authrate:";
    private static final Duration WINDOW = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    public ValkeyAuthRateCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<Double> getAuthRate(String pspConnector, String cardBrand, String cardType, String issuingCountry) {
        String key = segmentedKey(pspConnector, cardBrand, cardType, issuingCountry);
        return computeRate(key);
    }

    @Override
    public Optional<Double> getOverallAuthRate(String pspConnector) {
        return computeRate(overallKey(pspConnector));
    }

    @Override
    public void recordResult(String pspConnector, String cardBrand, String cardType,
                              String issuingCountry, boolean authorized) {
        try {
            // Update overall counter
            String overallKey = overallKey(pspConnector);
            redis.opsForHash().increment(overallKey, "total", 1);
            if (authorized) redis.opsForHash().increment(overallKey, "success", 1);
            redis.expire(overallKey, WINDOW);

            // Update segmented counter if card attributes provided
            if (cardBrand != null || cardType != null) {
                String segKey = segmentedKey(pspConnector, cardBrand, cardType, issuingCountry);
                redis.opsForHash().increment(segKey, "total", 1);
                if (authorized) redis.opsForHash().increment(segKey, "success", 1);
                redis.expire(segKey, WINDOW);
            }
        } catch (Exception e) {
            LOG.warn("Failed to record auth result for {}: {}", pspConnector, e.getMessage());
        }
    }

    @Override
    public long getSampleSize(String pspConnector) {
        try {
            Object total = redis.opsForHash().get(overallKey(pspConnector), "total");
            return total != null ? Long.parseLong(total.toString()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private Optional<Double> computeRate(String key) {
        try {
            Object success = redis.opsForHash().get(key, "success");
            Object total = redis.opsForHash().get(key, "total");
            if (success == null || total == null) return Optional.empty();

            long s = Long.parseLong(success.toString());
            long t = Long.parseLong(total.toString());
            return t > 0 ? Optional.of((double) s / t) : Optional.empty();
        } catch (Exception e) {
            LOG.warn("Failed to compute auth rate for {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private String overallKey(String psp) {
        return PREFIX + psp;
    }

    private String segmentedKey(String psp, String brand, String type, String country) {
        return PREFIX + psp + ":" +
                (brand != null ? brand : "*") + ":" +
                (type != null ? type : "*") + ":" +
                (country != null ? country : "*");
    }
}
