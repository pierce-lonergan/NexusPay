package io.nexuspay.fraud.application.dto;

import io.nexuspay.fraud.domain.model.RuleAction;

import java.util.Map;

/**
 * Request to update an existing fraud rule.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public record FraudRuleUpdateRequest(
        Map<String, Object> conditionDsl,
        RuleAction action,
        Integer scoreAdjustment,
        Integer priority,
        String abTestGroup,
        Double abTestTraffic,
        Boolean enabled
) {}
