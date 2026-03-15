package io.nexuspay.fraud.application.port.out;

import io.nexuspay.fraud.domain.model.FraudRule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository port for fraud rule persistence.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public interface FraudRuleRepository {

    FraudRule save(FraudRule rule);

    Optional<FraudRule> findById(UUID id);

    List<FraudRule> findByTenantId(String tenantId);

    List<FraudRule> findActiveByTenantId(String tenantId);

    void deleteById(UUID id);
}
