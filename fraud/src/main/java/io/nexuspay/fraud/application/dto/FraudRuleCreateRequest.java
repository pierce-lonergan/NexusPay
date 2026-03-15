package io.nexuspay.fraud.application.dto;

import io.nexuspay.fraud.domain.model.RuleAction;
import io.nexuspay.fraud.domain.model.RuleType;

import java.util.Map;

/**
 * Request to create a new fraud rule.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public record FraudRuleCreateRequest(
        String ruleName,
        RuleType ruleType,
        Map<String, Object> conditionDsl,
        RuleAction action,
        int scoreAdjustment,
        int priority,
        String abTestGroup,
        Double abTestTraffic
) {}
