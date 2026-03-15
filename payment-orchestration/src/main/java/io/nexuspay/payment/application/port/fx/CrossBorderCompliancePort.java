package io.nexuspay.payment.application.port.fx;

import io.nexuspay.payment.domain.fx.CountryRestriction;
import io.nexuspay.payment.domain.fx.CrossBorderRule;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for cross-border compliance checks.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
public interface CrossBorderCompliancePort {

    /**
     * Checks if a country is sanctioned or restricted.
     */
    Optional<CountryRestriction> checkCountryRestriction(String countryCode);

    /**
     * Gets the cross-border rule for a specific route.
     */
    Optional<CrossBorderRule> getRule(String sourceCountry, String destinationCountry);

    /**
     * Returns the list of sanctioned country codes.
     */
    List<String> getSanctionedCountries();

    /**
     * Checks if a transaction amount requires regulatory reporting.
     */
    boolean requiresReporting(String sourceCountry, String destinationCountry, BigDecimal amount, String currency);
}
