package io.nexuspay.payment.adapter.out.persistence.fx;

import io.nexuspay.payment.application.port.fx.MerchantCurrencyPrefsRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing MerchantCurrencyPrefsRepository using JPA.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Repository
public class MerchantCurrencyPrefsRepositoryAdapter implements MerchantCurrencyPrefsRepository {

    private final JpaMerchantCurrencyPrefsRepository jpaRepo;

    public MerchantCurrencyPrefsRepositoryAdapter(JpaMerchantCurrencyPrefsRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public Optional<MerchantCurrencyPrefs> findByTenantId(String tenantId) {
        return jpaRepo.findByTenantId(tenantId).map(this::toDomain);
    }

    @Override
    public MerchantCurrencyPrefs save(MerchantCurrencyPrefs prefs) {
        MerchantCurrencyPrefsEntity entity = toEntity(prefs);
        entity = jpaRepo.save(entity);
        return toDomain(entity);
    }

    private MerchantCurrencyPrefs toDomain(MerchantCurrencyPrefsEntity e) {
        return new MerchantCurrencyPrefs(
                e.getId(), e.getTenantId(), e.getSettlementCurrency(),
                e.isAutoConvert(), e.getFxMarkupBps(), e.getRateProvider(),
                e.getRateLockDurationMinutes()
        );
    }

    private MerchantCurrencyPrefsEntity toEntity(MerchantCurrencyPrefs p) {
        MerchantCurrencyPrefsEntity e = new MerchantCurrencyPrefsEntity();
        e.setId(p.id() != null ? p.id() : UUID.randomUUID());
        e.setTenantId(p.tenantId());
        e.setSettlementCurrency(p.settlementCurrency());
        e.setAutoConvert(p.autoConvert());
        e.setFxMarkupBps(p.fxMarkupBps());
        e.setRateProvider(p.rateProvider());
        e.setRateLockDurationMinutes(p.rateLockDurationMinutes());
        return e;
    }
}
