package io.nexuspay.fraud.application.dto;

import io.nexuspay.fraud.domain.model.FraudRule;
import io.nexuspay.fraud.domain.model.RuleAction;
import io.nexuspay.fraud.domain.model.RuleType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Response DTO for fraud rule API.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public record FraudRuleResponse(
        UUID id,
        String ruleName,
        RuleType ruleType,
        Map<String, Object> conditionDsl,
        RuleAction action,
        int scoreAdjustment,
        int priority,
        int version,
        String abTestGroup,
        Double abTestTraffic,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
    public static FraudRuleResponse from(FraudRule rule) {
        return new FraudRuleResponse(
                rule.getId(),
                rule.getRuleName(),
                rule.getRuleType(),
                rule.getCondition().dsl(),
                rule.getAction(),
                rule.getScoreAdjustment(),
                rule.getPriority(),
                rule.getVersion(),
                rule.getAbTestGroup(),
                rule.getAbTestTraffic(),
                rule.isEnabled(),
                rule.getCreatedAt(),
                rule.getUpdatedAt()
        );
    }
}
