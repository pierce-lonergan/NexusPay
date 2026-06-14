package io.nexuspay.payment.application.fx;

import io.nexuspay.common.rls.SystemTransactional;
import io.nexuspay.payment.application.port.fx.FxRateLockRepository;
import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository;
import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository.MerchantCurrencyPrefs;
import io.nexuspay.payment.domain.fx.FxRate;
import io.nexuspay.payment.domain.fx.FxRateLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages FX rate locking for the payment lifecycle.
 * A rate lock guarantees the exchange rate from payment intent creation
 * through to capture/settlement.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Service
public class FxRateLockService {

    private static final Logger LOG = LoggerFactory.getLogger(FxRateLockService.class);
    private static final Duration DEFAULT_LOCK_DURATION = Duration.ofMinutes(15);
    private static final Duration MAX_LOCK_DURATION = Duration.ofHours(1);

    private final FxRateLockRepository lockRepository;
    private final FxRateService rateService;
    private final MerchantCurrencyPrefsRepository prefsRepository;

    public FxRateLockService(FxRateLockRepository lockRepository,
                             FxRateService rateService,
                             MerchantCurrencyPrefsRepository prefsRepository) {
        this.lockRepository = lockRepository;
        this.rateService = rateService;
        this.prefsRepository = prefsRepository;
    }

    /**
     * Locks the current FX rate for a payment.
     *
     * @param tenantId      the merchant tenant
     * @param fromCurrency  presentment currency
     * @param toCurrency    settlement currency
     * @return the rate lock
     */
    public FxRateLock lockRate(String tenantId, String fromCurrency, String toCurrency) {
        Duration lockDuration = getLockDuration(tenantId);
        FxRate rate = rateService.getRate(tenantId, fromCurrency, toCurrency);
        FxRateLock lock = FxRateLock.create(tenantId, rate, lockDuration);

        lock = lockRepository.save(lock);
        LOG.info("Locked FX rate {} for {}/{} (tenant: {}, expires: {})",
                lock.getRate(), fromCurrency, toCurrency, tenantId, lock.getExpiresAt());
        return lock;
    }

    /**
     * Refreshes an expired rate lock with the current rate.
     */
    public FxRateLock refreshLock(UUID lockId) {
        FxRateLock existing = lockRepository.findById(lockId)
                .orElseThrow(() -> new IllegalArgumentException("Rate lock not found: " + lockId));

        if (existing.isValid()) {
            LOG.debug("Rate lock {} is still valid, no refresh needed", lockId);
            return existing;
        }

        if (existing.isConsumed()) {
            throw new IllegalStateException("Cannot refresh consumed rate lock: " + lockId);
        }

        // Create a new lock with fresh rate
        Duration lockDuration = getLockDuration(existing.getTenantId());
        FxRate freshRate = rateService.getRate(
                existing.getTenantId(), existing.getFromCurrency(), existing.getToCurrency());
        FxRateLock newLock = FxRateLock.create(existing.getTenantId(), freshRate, lockDuration);
        if (existing.getPaymentId() != null) {
            newLock.assignPayment(existing.getPaymentId());
        }

        newLock = lockRepository.save(newLock);
        LOG.info("Refreshed expired rate lock {} → new lock {} with rate {}",
                lockId, newLock.getId(), newLock.getRate());
        return newLock;
    }

    /**
     * Validates and retrieves a rate lock for use.
     * If expired, automatically refreshes.
     */
    public FxRateLock getValidLock(UUID lockId) {
        FxRateLock lock = lockRepository.findById(lockId)
                .orElseThrow(() -> new IllegalArgumentException("Rate lock not found: " + lockId));

        if (lock.isConsumed()) {
            return lock; // Already used — return for reference
        }

        if (lock.isExpired()) {
            LOG.info("Rate lock {} expired, refreshing automatically", lockId);
            return refreshLock(lockId);
        }

        return lock;
    }

    /**
     * Retrieves the rate lock associated with a payment.
     */
    public Optional<FxRateLock> findByPaymentId(String paymentId) {
        return lockRepository.findByPaymentId(paymentId);
    }

    /**
     * Checks if a rate lock is still valid.
     */
    public boolean isLockValid(UUID lockId) {
        return lockRepository.findById(lockId)
                .map(FxRateLock::isValid)
                .orElse(false);
    }

    /**
     * Scheduled cleanup of expired, unconsumed rate locks.
     * Runs every 30 minutes.
     */
    @SystemTransactional
    @Scheduled(cron = "0 0/30 * * * *")
    public void cleanupExpiredLocks() {
        int cleaned = lockRepository.cleanupExpiredLocks();
        if (cleaned > 0) {
            LOG.info("Cleaned up {} expired FX rate locks", cleaned);
        }
    }

    private Duration getLockDuration(String tenantId) {
        return prefsRepository.findByTenantId(tenantId)
                .map(prefs -> {
                    Duration d = Duration.ofMinutes(prefs.rateLockDurationMinutes());
                    return d.compareTo(MAX_LOCK_DURATION) > 0 ? MAX_LOCK_DURATION : d;
                })
                .orElse(DEFAULT_LOCK_DURATION);
    }
}
