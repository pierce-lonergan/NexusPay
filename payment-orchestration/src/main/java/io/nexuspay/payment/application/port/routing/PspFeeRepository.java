package io.nexuspay.payment.application.port.routing;

import io.nexuspay.payment.domain.routing.PspFeeModel;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for PSP fee model data.
 *
 * @since 0.3.0 (Sprint 3.3)
 * @since 0.3.1 (GAP-049 — card-brand-specific lookup)
 */
public interface PspFeeRepository {

    PspFeeModel save(PspFeeModel model);

    Optional<PspFeeModel> findById(UUID id);

    List<PspFeeModel> findByTenantAndCurrency(String tenantId, String currency);

    Optional<PspFeeModel> findEffective(String tenantId, String pspConnector, String currency, LocalDate date);

    List<PspFeeModel> findByTenantId(String tenantId);

    /**
     * Finds the most specific effective fee model for a PSP given card attributes.
     * Searches all effective models for the PSP and selects the one with the highest
     * specificity score that matches the given card brand, type, and domestic status.
     * Falls back to the generic (no card attributes) model if no specific match exists.
     *
     * @param tenantId      the tenant
     * @param pspConnector  the PSP connector name
     * @param currency      the payment currency
     * @param date          the effective date
     * @param cardBrand     the card network (e.g., "VISA", "MASTERCARD"), nullable
     * @param cardType      the card type (e.g., "CREDIT", "DEBIT"), nullable
     * @param isDomestic    whether the transaction is domestic, nullable
     * @return the most specific matching fee model
     */
    default Optional<PspFeeModel> findBestMatch(String tenantId, String pspConnector, String currency,
                                                  LocalDate date, String cardBrand, String cardType,
                                                  Boolean isDomestic) {
        List<PspFeeModel> candidates = findByTenantAndCurrency(tenantId, currency).stream()
                .filter(f -> f.pspConnector().equals(pspConnector))
                .filter(f -> f.isEffective(date))
                .filter(f -> f.matchesCard(cardBrand, cardType, isDomestic))
                .toList();

        // Select the most specific match
        return candidates.stream()
                .max(Comparator.comparingInt(PspFeeModel::specificity));
    }
}
