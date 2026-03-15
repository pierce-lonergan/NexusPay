package io.nexuspay.payment.application.port.fx;

import io.nexuspay.payment.domain.fx.CurrencyCapability;

import java.util.List;

/**
 * Outbound port for PSP currency capability persistence.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public interface CurrencyCapabilityRepository {

    List<CurrencyCapability> findByPspConnector(String pspConnector);

    List<CurrencyCapability> findByCurrencyCode(String currencyCode);

    /**
     * Finds PSPs that support a specific currency for presentment.
     */
    List<CurrencyCapability> findPresentmentCapable(String currencyCode);

    /**
     * Finds PSPs that support a specific currency for settlement.
     */
    List<CurrencyCapability> findSettlementCapable(String currencyCode);

    CurrencyCapability save(CurrencyCapability capability);
}
