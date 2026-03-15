package io.nexuspay.ledger.application.port;

import io.nexuspay.ledger.domain.FxGainLossAccount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for FX gain/loss account persistence.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public interface FxGainLossAccountRepository {

    FxGainLossAccount save(FxGainLossAccount account);

    Optional<FxGainLossAccount> findByTenantIdAndCurrencyPair(String tenantId, String currencyPair);

    List<FxGainLossAccount> findByTenantId(String tenantId);

    Optional<FxGainLossAccount> findById(UUID id);
}
