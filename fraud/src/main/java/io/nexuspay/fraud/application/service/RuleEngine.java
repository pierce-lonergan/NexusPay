package io.nexuspay.fraud.application.service;

import io.nexuspay.fraud.application.dto.PaymentContext;
import io.nexuspay.fraud.domain.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluates individual fraud rules against a payment context.
 *
 * <p>Supports five rule types: VELOCITY, AMOUNT_THRESHOLD,
 * GEO_RESTRICTION, BIN_CHECK, and DEVICE_FINGERPRINT.</p>
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    /**
     * Evaluates a single rule against the payment context.
     *
     * @return the RiskSignal if the rule triggers, or null if it doesn't
     */
    public RiskSignal evaluate(FraudRule rule, PaymentContext context) {
        return switch (rule.getRuleType()) {
            case VELOCITY -> evaluateVelocity(rule, context);
            case AMOUNT_THRESHOLD -> evaluateAmountThreshold(rule, context);
            case GEO_RESTRICTION -> evaluateGeoRestriction(rule, context);
            case BIN_CHECK -> evaluateBinCheck(rule, context);
            case DEVICE_FINGERPRINT -> evaluateDeviceFingerprint(rule, context);
        };
    }

    private RiskSignal evaluateVelocity(FraudRule rule, PaymentContext context) {
        // Velocity checks require external state (transaction count in window).
        // The RuleEvaluationPipeline provides velocity counts via Valkey.
        // Here we check the condition structure is valid and return a signal
        // indicating this rule type matched structurally — actual count check
        // is delegated to the pipeline.
        int maxCount = rule.getCondition().getInt("max_count", Integer.MAX_VALUE);
        int windowMinutes = rule.getCondition().getInt("window_minutes", 60);

        if (maxCount < Integer.MAX_VALUE) {
            return new RiskSignal(
                    "velocity_rule",
                    rule.getRuleName(),
                    rule.getScoreAdjustment(),
                    String.format("Velocity check: max %d in %d minutes for %s",
                            maxCount, windowMinutes, rule.getCondition().getString("field"))
            );
        }
        return null;
    }

    private RiskSignal evaluateAmountThreshold(FraudRule rule, PaymentContext context) {
        long threshold = rule.getCondition().getLong("amount", Long.MAX_VALUE);
        String operator = rule.getCondition().getString("operator");
        String currency = rule.getCondition().getString("currency");

        // Currency must match if specified
        if (currency != null && !currency.equalsIgnoreCase(context.currency())) {
            return null;
        }

        boolean triggered = switch (operator != null ? operator : "gt") {
            case "gt" -> context.amountMinorUnits() > threshold;
            case "gte" -> context.amountMinorUnits() >= threshold;
            case "lt" -> context.amountMinorUnits() < threshold;
            case "lte" -> context.amountMinorUnits() <= threshold;
            default -> context.amountMinorUnits() > threshold;
        };

        if (triggered) {
            return new RiskSignal(
                    "amount_threshold_rule",
                    rule.getRuleName(),
                    rule.getScoreAdjustment(),
                    String.format("Amount %d %s threshold %d %s",
                            context.amountMinorUnits(), operator, threshold,
                            currency != null ? currency : "")
            );
        }
        return null;
    }

    private RiskSignal evaluateGeoRestriction(FraudRule rule, PaymentContext context) {
        List<String> blockedCountries = rule.getCondition().getStringList("blocked_countries");
        if (context.ipCountry() != null && blockedCountries.contains(context.ipCountry().toUpperCase())) {
            return new RiskSignal(
                    "geo_restriction_rule",
                    rule.getRuleName(),
                    rule.getScoreAdjustment(),
                    String.format("IP country %s is in blocked list", context.ipCountry())
            );
        }
        return null;
    }

    private RiskSignal evaluateBinCheck(FraudRule rule, PaymentContext context) {
        List<String> highRiskBins = rule.getCondition().getStringList("high_risk_bins");
        String matchType = rule.getCondition().getString("match_type");

        if (context.cardBin() == null) return null;

        boolean matched = "prefix".equals(matchType)
                ? highRiskBins.stream().anyMatch(bin -> context.cardBin().startsWith(bin))
                : highRiskBins.contains(context.cardBin());

        if (matched) {
            return new RiskSignal(
                    "bin_check_rule",
                    rule.getRuleName(),
                    rule.getScoreAdjustment(),
                    String.format("Card BIN %s matched high-risk list", context.cardBin())
            );
        }
        return null;
    }

    private RiskSignal evaluateDeviceFingerprint(FraudRule rule, PaymentContext context) {
        // Device fingerprint evaluation checks reputation and age.
        // Actual reputation lookup is performed in DeviceFingerprintMatcher;
        // this rule type signals that device checks should contribute to scoring.
        if (context.deviceFingerprintHash() == null) {
            return new RiskSignal(
                    "device_fingerprint_rule",
                    rule.getRuleName(),
                    Math.abs(rule.getScoreAdjustment()),
                    "No device fingerprint provided — higher risk"
            );
        }
        return null; // Detailed device check handled by DeviceFingerprintMatcher
    }
}
