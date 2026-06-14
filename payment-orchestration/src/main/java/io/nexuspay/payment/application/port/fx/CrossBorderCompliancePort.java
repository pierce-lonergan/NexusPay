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

    /**
     * Whether sanctions screening is currently usable (B-026 fail-closed).
     *
     * <p>Returns {@code false} when the screen has NO list to match against at all —
     * the decision path MUST then block/REVIEW rather than ALLOW (a missing list is
     * not a clean transaction). A healthy boot-time static baseline (KP/IR/SY/CU)
     * counts as a valid minimal screen so the first scheduled refresh is not a
     * self-inflicted outage; "OFAC feed unreachable" alone is surfaced via health,
     * not by failing every transaction, as long as the static baseline is intact.</p>
     *
     * @return {@code true} when at least the static sanctions baseline is loaded and
     *         the list is not stale beyond the configured tolerance; {@code false} otherwise
     */
    boolean isScreeningAvailable();
}
