package io.nexuspay.payment.adapter.out.persistence.fx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for currency capability entities.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public interface JpaCurrencyCapabilityRepository extends JpaRepository<CurrencyCapabilityEntity, UUID> {

    List<CurrencyCapabilityEntity> findByPspConnectorAndEnabledTrue(String pspConnector);

    List<CurrencyCapabilityEntity> findByCurrencyCodeAndEnabledTrue(String currencyCode);

    List<CurrencyCapabilityEntity> findByCurrencyCodeAndSupportsPresentmentTrueAndEnabledTrue(String currencyCode);

    List<CurrencyCapabilityEntity> findByCurrencyCodeAndSupportsSettlementTrueAndEnabledTrue(String currencyCode);
}
