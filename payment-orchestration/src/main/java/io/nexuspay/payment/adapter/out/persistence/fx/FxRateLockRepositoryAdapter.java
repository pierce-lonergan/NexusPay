package io.nexuspay.payment.adapter.out.persistence.fx;

import io.nexuspay.payment.application.port.fx.FxRateLockRepository;
import io.nexuspay.payment.domain.fx.FxRateLock;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing FxRateLockRepository using JPA.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Repository
public class FxRateLockRepositoryAdapter implements FxRateLockRepository {

    private final JpaFxRateLockRepository jpaRepo;

    public FxRateLockRepositoryAdapter(JpaFxRateLockRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    @Transactional
    public FxRateLock save(FxRateLock lock) {
        FxRateLockEntity entity = toEntity(lock);
        entity = jpaRepo.save(entity);
        return toDomain(entity);
    }

    @Override
    public Optional<FxRateLock> findById(UUID id) {
        return jpaRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<FxRateLock> findByPaymentId(String paymentId) {
        return jpaRepo.findByPaymentId(paymentId).map(this::toDomain);
    }

    @Override
    @Transactional
    public int cleanupExpiredLocks() {
        return jpaRepo.cleanupExpiredLocks(Instant.now());
    }

    private FxRateLockEntity toEntity(FxRateLock lock) {
        FxRateLockEntity entity = new FxRateLockEntity();
        entity.setId(lock.getId());
        entity.setTenantId(lock.getTenantId());
        entity.setPaymentId(lock.getPaymentId());
        entity.setFromCurrency(lock.getFromCurrency());
        entity.setToCurrency(lock.getToCurrency());
        entity.setRate(lock.getRate());
        entity.setInverseRate(lock.getInverseRate());
        entity.setRateProvider(lock.getRateProvider());
        entity.setLockedAt(lock.getLockedAt());
        entity.setExpiresAt(lock.getExpiresAt());
        entity.setConsumed(lock.isConsumed());
        entity.setConsumedAt(lock.getConsumedAt());
        return entity;
    }

    private FxRateLock toDomain(FxRateLockEntity entity) {
        return new FxRateLock(
                entity.getId(), entity.getTenantId(), entity.getPaymentId(),
                entity.getFromCurrency(), entity.getToCurrency(),
                entity.getRate(), entity.getInverseRate(),
                entity.getRateProvider(), entity.getLockedAt(), entity.getExpiresAt(),
                entity.isConsumed(), entity.getConsumedAt()
        );
    }
}
