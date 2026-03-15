package io.nexuspay.ledger.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for FX gain/loss accounts.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public interface JpaFxGainLossAccountRepository extends JpaRepository<FxGainLossAccountEntity, UUID> {

    Optional<FxGainLossAccountEntity> findByTenantIdAndCurrencyPair(String tenantId, String currencyPair);

    List<FxGainLossAccountEntity> findByTenantId(String tenantId);
}
