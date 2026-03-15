package io.nexuspay.payment.adapter.out.persistence.fx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for merchant currency preferences.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public interface JpaMerchantCurrencyPrefsRepository extends JpaRepository<MerchantCurrencyPrefsEntity, UUID> {

    Optional<MerchantCurrencyPrefsEntity> findByTenantId(String tenantId);
}
