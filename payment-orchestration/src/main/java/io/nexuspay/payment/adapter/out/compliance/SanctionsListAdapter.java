package io.nexuspay.payment.adapter.out.compliance;

import io.nexuspay.payment.application.port.fx.CrossBorderCompliancePort;
import io.nexuspay.payment.domain.fx.CountryRestriction;
import io.nexuspay.payment.domain.fx.CrossBorderRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/**
 * Compliance adapter backed by a configurable sanctions list.
 * In production, this would integrate with OFAC SDN, EU sanctions,
 * and other regulatory databases.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Component
public class SanctionsListAdapter implements CrossBorderCompliancePort {

    private final Set<String> sanctionedCountries;
    private final BigDecimal reportingThreshold;

    public SanctionsListAdapter(
            @Value("${nexuspay.fx.compliance.sanctioned-countries:KP,IR,SY,CU}") List<String> sanctionedCountries,
            @Value("${nexuspay.fx.compliance.cross-border-amount-reporting-threshold:10000}") BigDecimal reportingThreshold) {
        this.sanctionedCountries = new HashSet<>();
        for (String code : sanctionedCountries) {
            this.sanctionedCountries.add(code.trim().toUpperCase());
        }
        this.reportingThreshold = reportingThreshold;
    }

    @Override
    public Optional<CountryRestriction> checkCountryRestriction(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            return Optional.empty();
        }

        String normalized = countryCode.trim().toUpperCase();
        if (sanctionedCountries.contains(normalized)) {
            return Optional.of(new CountryRestriction(
                    normalized,
                    CountryRestriction.RestrictionType.SANCTIONED,
                    "Country is subject to comprehensive sanctions"
            ));
        }

        // High-risk countries (configurable, hardcoded examples for now)
        Set<String> highRisk = Set.of("AF", "BY", "MM", "VE", "ZW", "LY", "SO", "YE", "SD");
        if (highRisk.contains(normalized)) {
            return Optional.of(new CountryRestriction(
                    normalized,
                    CountryRestriction.RestrictionType.HIGH_RISK,
                    "Country is classified as high-risk for financial transactions"
            ));
        }

        return Optional.empty();
    }

    @Override
    public Optional<CrossBorderRule> getRule(String sourceCountry, String destinationCountry) {
        // Default cross-border rule: reporting required above threshold
        if (sourceCountry != null && destinationCountry != null
                && !sourceCountry.equalsIgnoreCase(destinationCountry)) {
            return Optional.of(new CrossBorderRule(
                    sourceCountry, destinationCountry,
                    reportingThreshold, "USD",
                    false, false
            ));
        }
        return Optional.empty();
    }

    @Override
    public List<String> getSanctionedCountries() {
        return new ArrayList<>(sanctionedCountries);
    }

    @Override
    public boolean requiresReporting(String sourceCountry, String destinationCountry,
                                     BigDecimal amount, String currency) {
        if (sourceCountry == null || destinationCountry == null) return false;
        if (sourceCountry.equalsIgnoreCase(destinationCountry)) return false;

        // Cross-border transactions above the threshold require reporting
        return amount.compareTo(reportingThreshold) >= 0;
    }
}
