package io.nexuspay.payment.application.fx;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.payment.application.port.fx.CrossBorderCompliancePort;
import io.nexuspay.payment.domain.fx.CountryRestriction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cross-border compliance enforcement.
 * Validates payments against sanctions lists, country restrictions,
 * and regulatory reporting requirements.
 *
 * @since 0.3.0 (Sprint 3.2)
 */
@Service
public class CrossBorderComplianceService {

    private static final Logger LOG = LoggerFactory.getLogger(CrossBorderComplianceService.class);

    private final CrossBorderCompliancePort compliancePort;

    public CrossBorderComplianceService(CrossBorderCompliancePort compliancePort) {
        this.compliancePort = compliancePort;
    }

    /**
     * Result of a compliance check.
     */
    public record ComplianceResult(
            boolean allowed,
            boolean requiresReporting,
            boolean requiresEnhancedDueDiligence,
            List<String> flags,
            String blockReason
    ) {
        public static ComplianceResult blocked(String reason) {
            return new ComplianceResult(false, false, false, List.of(), reason);
        }
    }

    /**
     * Validates a cross-border payment against compliance rules.
     *
     * @param sourceCountry      IP country or sender country
     * @param destinationCountry merchant/recipient country
     * @param amount             transaction amount
     * @param currency           transaction currency
     * @return compliance check result
     */
    public ComplianceResult validateTransaction(
            String sourceCountry, String destinationCountry,
            BigDecimal amount, String currency) {

        List<String> flags = new ArrayList<>();

        // Check source country sanctions
        Optional<CountryRestriction> sourceRestriction = compliancePort.checkCountryRestriction(sourceCountry);
        if (sourceRestriction.isPresent() && sourceRestriction.get().isBlocking()) {
            LOG.warn("Transaction blocked: source country {} is sanctioned", sourceCountry);
            return ComplianceResult.blocked(
                    "Transaction from sanctioned country: " + sourceCountry);
        }
        sourceRestriction.ifPresent(r -> {
            if (r.type() == CountryRestriction.RestrictionType.HIGH_RISK) {
                flags.add("high_risk_source_country:" + sourceCountry);
            }
        });

        // Check destination country sanctions
        Optional<CountryRestriction> destRestriction = compliancePort.checkCountryRestriction(destinationCountry);
        if (destRestriction.isPresent() && destRestriction.get().isBlocking()) {
            LOG.warn("Transaction blocked: destination country {} is sanctioned", destinationCountry);
            return ComplianceResult.blocked(
                    "Transaction to sanctioned country: " + destinationCountry);
        }
        destRestriction.ifPresent(r -> {
            if (r.type() == CountryRestriction.RestrictionType.HIGH_RISK) {
                flags.add("high_risk_destination_country:" + destinationCountry);
            }
        });

        // Check reporting requirements
        boolean requiresReporting = compliancePort.requiresReporting(
                sourceCountry, destinationCountry, amount, currency);
        if (requiresReporting) {
            flags.add("cross_border_reporting_required");
            LOG.info("Cross-border reporting required for {} {} from {} to {}",
                    amount, currency, sourceCountry, destinationCountry);
        }

        // Check enhanced due diligence
        boolean requiresEdd = compliancePort.getRule(sourceCountry, destinationCountry)
                .map(rule -> rule.requiresEnhancedDueDiligence())
                .orElse(false);
        if (requiresEdd) {
            flags.add("enhanced_due_diligence_required");
        }

        return new ComplianceResult(true, requiresReporting, requiresEdd, flags, null);
    }

    /**
     * Validates a payment and throws if blocked.
     */
    public ComplianceResult validateOrThrow(
            String sourceCountry, String destinationCountry,
            BigDecimal amount, String currency) {

        ComplianceResult result = validateTransaction(sourceCountry, destinationCountry, amount, currency);
        if (!result.allowed()) {
            throw new PaymentException("cross_border_blocked", result.blockReason());
        }
        return result;
    }
}
