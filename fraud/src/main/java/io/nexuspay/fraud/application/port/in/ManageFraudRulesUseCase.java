package io.nexuspay.fraud.application.port.in;

import io.nexuspay.fraud.application.dto.FraudRuleCreateRequest;
import io.nexuspay.fraud.application.dto.FraudRuleResponse;
import io.nexuspay.fraud.application.dto.FraudRuleUpdateRequest;

import java.util.List;
import java.util.UUID;

/**
 * Inbound port for managing fraud detection rules.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public interface ManageFraudRulesUseCase {

    FraudRuleResponse createRule(FraudRuleCreateRequest request, String tenantId, String createdBy);

    FraudRuleResponse updateRule(UUID ruleId, FraudRuleUpdateRequest request, String tenantId);

    void disableRule(UUID ruleId, String tenantId);

    void enableRule(UUID ruleId, String tenantId);

    FraudRuleResponse getRule(UUID ruleId, String tenantId);

    List<FraudRuleResponse> listRules(String tenantId);
}
