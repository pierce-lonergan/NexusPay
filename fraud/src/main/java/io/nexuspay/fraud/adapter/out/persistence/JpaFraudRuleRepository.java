package io.nexuspay.fraud.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for fraud rules.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
public interface JpaFraudRuleRepository extends JpaRepository<FraudRuleEntity, UUID> {

    List<FraudRuleEntity> findByTenantId(String tenantId);

    List<FraudRuleEntity> findByTenantIdAndEnabledTrue(String tenantId);
}
