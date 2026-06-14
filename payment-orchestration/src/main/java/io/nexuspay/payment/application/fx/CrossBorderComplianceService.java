package io.nexuspay.payment.application.fx;

import io.nexuspay.common.exception.PaymentException;
import io.nexuspay.payment.application.port.fx.CrossBorderCompliancePort;
import io.nexuspay.payment.domain.fx.CountryRestriction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    /** Generic reason surfaced to the caller when screening cannot run (B-026, B-028: no country echoed). */
    static final String SCREENING_UNAVAILABLE_REASON = "Sanctions screening is temporarily unavailable";

    /** Flag a REVIEW outcome carries when geography cannot be server-authoritatively resolved (B-025). */
    public static final String GEO_UNKNOWN_REVIEW_FLAG = "geo_unknown_review";

    private final CrossBorderCompliancePort compliancePort;
    private final boolean unknownGeoReviewEnabled;

    public CrossBorderComplianceService(
            CrossBorderCompliancePort compliancePort,
            @Value("${nexuspay.fx.compliance.unknown-geo-review-enabled:true}") boolean unknownGeoReviewEnabled) {
        this.compliancePort = compliancePort;
        this.unknownGeoReviewEnabled = unknownGeoReviewEnabled;
    }

    /**
     * Result of a compliance check.
     *
     * @param requiresReview true when the transaction must be routed to REVIEW/EDD rather
     *                       than auto-allowed — e.g. geography could not be resolved on a
     *                       cross-border-capable flow (B-025 fail-closed: unknown ≠ allow).
     *                       {@code allowed} stays true (the PSP authorizes) but the gate must
     *                       hold capture; a hard sanctions hit uses {@link #blocked} instead.
     */
    public record ComplianceResult(
            boolean allowed,
            boolean requiresReporting,
            boolean requiresEnhancedDueDiligence,
            List<String> flags,
            String blockReason,
            boolean requiresReview
    ) {
        /** Backward-compatible 5-arg constructor (no REVIEW): allowed transactions, hard blocks. */
        public ComplianceResult(boolean allowed, boolean requiresReporting,
                                boolean requiresEnhancedDueDiligence, List<String> flags, String blockReason) {
            this(allowed, requiresReporting, requiresEnhancedDueDiligence, flags, blockReason, false);
        }

        public static ComplianceResult blocked(String reason) {
            return new ComplianceResult(false, false, false, List.of(), reason, false);
        }

        /**
         * Unknown-geography outcome (B-025): authorize but force REVIEW/EDD + capture hold.
         * This is NOT a clean ALLOW — the gate must route it to the capture-held REVIEW path.
         */
        public static ComplianceResult review(List<String> flags) {
            return new ComplianceResult(true, false, true, flags, null, true);
        }
    }

    /**
     * Validates a cross-border payment against compliance rules.
     *
     * <p>Backward-compatible entry point: callers that have not yet resolved
     * server-authoritative geography (e.g. the FX validate endpoint) treat both legs as
     * "known" with the supplied values — the B-025 unknown-geo REVIEW branch only fires
     * via {@link #validateTransaction(String, String, BigDecimal, String, GeographyTrust)}.</p>
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
        return validateTransaction(sourceCountry, destinationCountry, amount, currency,
                GeographyTrust.legacyAssumeKnown());
    }

    /**
     * Trust metadata for the supplied geography (B-025). Tells the compliance check whether each
     * leg was resolved from a SERVER-authoritative source (merchant config / trusted edge) or is
     * unknown. Unknown legs on a cross-border-capable flow must REVIEW, not silently ALLOW.
     *
     * @param destinationKnown   destination resolved from server-authoritative config
     * @param sourceKnown        source resolved from a trusted signal (edge geo-IP)
     * @param crossBorderCapable the flow can move money across borders (so unknown geo matters)
     */
    public record GeographyTrust(boolean destinationKnown, boolean sourceKnown, boolean crossBorderCapable) {
        /**
         * Legacy callers without resolved geography: assume both legs known + cross-border-capable.
         * This preserves the previous behavior for the FX validate endpoint (it still BLOCKS
         * sanctioned countries; it just does not gain the unknown-geo REVIEW semantics).
         */
        public static GeographyTrust legacyAssumeKnown() {
            return new GeographyTrust(true, true, true);
        }
    }

    /**
     * Geography-aware validation (B-025 fail-closed). Behaves like the 4-arg overload for the
     * sanctions BLOCK + reporting/EDD checks, but additionally routes a transaction to REVIEW
     * when geography could not be server-authoritatively resolved on a cross-border-capable flow.
     */
    public ComplianceResult validateTransaction(
            String sourceCountry, String destinationCountry,
            BigDecimal amount, String currency, GeographyTrust trust) {

        // FAIL CLOSED (B-026): if the sanctions screen cannot run at all (no list loaded /
        // stale beyond tolerance), do NOT allow — block. A clean country like "US" is blocked
        // when screening is down: a missing list is not a clean transaction.
        if (!compliancePort.isScreeningAvailable()) {
            LOG.error("Sanctions screening UNAVAILABLE — failing closed (blocking). "
                    + "src/dest withheld from log detail per B-028.");
            return ComplianceResult.blocked(SCREENING_UNAVAILABLE_REASON);
        }

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

        // FAIL CLOSED (B-025): geography that could not be server-authoritatively resolved on a
        // cross-border-capable flow must NOT silently ALLOW. A caller that omits/forges a country
        // would otherwise look "domestic/clean". Route to REVIEW/EDD (capture held) instead.
        //
        // A "domestic-only escape" (no REVIEW) is granted ONLY when BOTH legs are server-known,
        // equal, and non-sanctioned (handled implicitly: if known + equal the flow is not
        // cross-border-capable, so trust.crossBorderCapable() will be false). We never INFER
        // "domestic" from a missing leg.
        boolean geoUnknown = !trust.destinationKnown() || !trust.sourceKnown();
        if (unknownGeoReviewEnabled && trust.crossBorderCapable() && geoUnknown) {
            if (!trust.destinationKnown()) {
                flags.add("destination_country_unknown");
            }
            if (!trust.sourceKnown()) {
                flags.add("source_country_unknown");
            }
            flags.add(GEO_UNKNOWN_REVIEW_FLAG);
            LOG.warn("Geography not server-authoritative on cross-border-capable flow "
                    + "(destKnown={}, srcKnown={}) — routing to REVIEW/EDD (fail-closed, not allow)",
                    trust.destinationKnown(), trust.sourceKnown());
            return ComplianceResult.review(flags);
        }

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
     * Validates a payment and throws if blocked. Backward-compatible (legacy assume-known geography).
     */
    public ComplianceResult validateOrThrow(
            String sourceCountry, String destinationCountry,
            BigDecimal amount, String currency) {
        return validateOrThrow(sourceCountry, destinationCountry, amount, currency,
                GeographyTrust.legacyAssumeKnown());
    }

    /**
     * Geography-aware validate-or-throw (B-025/B-026). Throws {@code cross_border_blocked} for a
     * hard block (sanctioned country OR screening unavailable). A REVIEW outcome (unknown geo) is
     * NOT thrown — it is returned with {@code requiresReview()==true} so the gate can hold capture.
     */
    public ComplianceResult validateOrThrow(
            String sourceCountry, String destinationCountry,
            BigDecimal amount, String currency, GeographyTrust trust) {

        ComplianceResult result = validateTransaction(sourceCountry, destinationCountry, amount, currency, trust);
        if (!result.allowed()) {
            // PaymentException is (message, errorCode) — these were swapped, so the
            // error code was the reason text and never matched the handler's
            // "cross_border_blocked" case (silently became a 422 with a code-as-message).
            // blockReason is GENERIC (no country echoed) when screening is unavailable (B-028).
            throw new PaymentException(result.blockReason(), "cross_border_blocked");
        }
        return result;
    }
}
