package io.nexuspay.payment.adapter.out.persistence.fx;

import io.nexuspay.payment.application.port.fx.CurrencyCapabilityRepository;
import io.nexuspay.payment.domain.fx.CurrencyCapability;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Adapter implementing CurrencyCapabilityRepository using JPA.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Repository
public class CurrencyCapabilityRepositoryAdapter implements CurrencyCapabilityRepository {

    private final JpaCurrencyCapabilityRepository jpaRepo;

    public CurrencyCapabilityRepositoryAdapter(JpaCurrencyCapabilityRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public List<CurrencyCapability> findByPspConnector(String pspConnector) {
        return jpaRepo.findByPspConnectorAndEnabledTrue(pspConnector).stream()
                .map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<CurrencyCapability> findByCurrencyCode(String currencyCode) {
        return jpaRepo.findByCurrencyCodeAndEnabledTrue(currencyCode).stream()
                .map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<CurrencyCapability> findPresentmentCapable(String currencyCode) {
        return jpaRepo.findByCurrencyCodeAndSupportsPresentmentTrueAndEnabledTrue(currencyCode).stream()
                .map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<CurrencyCapability> findSettlementCapable(String currencyCode) {
        return jpaRepo.findByCurrencyCodeAndSupportsSettlementTrueAndEnabledTrue(currencyCode).stream()
                .map(this::toDomain).collect(Collectors.toList());
    }

    @Override
    public CurrencyCapability save(CurrencyCapability capability) {
        CurrencyCapabilityEntity entity = toEntity(capability);
        entity = jpaRepo.save(entity);
        return toDomain(entity);
    }

    private CurrencyCapability toDomain(CurrencyCapabilityEntity e) {
        return new CurrencyCapability(
                e.getId(), e.getPspConnector(), e.getCurrencyCode(),
                e.isSupportsPresentment(), e.isSupportsSettlement(), e.isSupportsDcc(),
                e.getMinAmount(), e.getMaxAmount(), e.isEnabled()
        );
    }

    private CurrencyCapabilityEntity toEntity(CurrencyCapability c) {
        CurrencyCapabilityEntity e = new CurrencyCapabilityEntity();
        e.setId(c.id() != null ? c.id() : UUID.randomUUID());
        e.setPspConnector(c.pspConnector());
        e.setCurrencyCode(c.currencyCode());
        e.setSupportsPresentment(c.supportsPresentment());
        e.setSupportsSettlement(c.supportsSettlement());
        e.setSupportsDcc(c.supportsDcc());
        e.setMinAmount(c.minAmount());
        e.setMaxAmount(c.maxAmount());
        e.setEnabled(c.enabled());
        return e;
    }
}
