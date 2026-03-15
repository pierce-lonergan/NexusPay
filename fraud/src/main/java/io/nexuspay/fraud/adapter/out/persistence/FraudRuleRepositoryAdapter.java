package io.nexuspay.fraud.adapter.out.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.fraud.application.port.out.FraudRuleRepository;
import io.nexuspay.fraud.domain.model.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter mapping between FraudRule domain model and FraudRuleEntity JPA entity.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Component
public class FraudRuleRepositoryAdapter implements FraudRuleRepository {

    private final JpaFraudRuleRepository jpaRepo;
    private final ObjectMapper objectMapper;

    public FraudRuleRepositoryAdapter(JpaFraudRuleRepository jpaRepo, ObjectMapper objectMapper) {
        this.jpaRepo = jpaRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public FraudRule save(FraudRule rule) {
        FraudRuleEntity entity = toEntity(rule);
        FraudRuleEntity saved = jpaRepo.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<FraudRule> findById(UUID id) {
        return jpaRepo.findById(id).map(this::toDomain);
    }

    @Override
    public List<FraudRule> findByTenantId(String tenantId) {
        return jpaRepo.findByTenantId(tenantId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<FraudRule> findActiveByTenantId(String tenantId) {
        return jpaRepo.findByTenantIdAndEnabledTrue(tenantId).stream().map(this::toDomain).toList();
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepo.deleteById(id);
    }

    private FraudRuleEntity toEntity(FraudRule rule) {
        FraudRuleEntity e = new FraudRuleEntity();
        e.setId(rule.getId());
        e.setTenantId(rule.getTenantId());
        e.setRuleName(rule.getRuleName());
        e.setRuleType(rule.getRuleType().name());
        try {
            e.setConditionDsl(objectMapper.writeValueAsString(rule.getCondition().dsl()));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to serialize rule condition DSL", ex);
        }
        e.setAction(rule.getAction().name());
        e.setScoreAdjustment(rule.getScoreAdjustment());
        e.setPriority(rule.getPriority());
        e.setVersion(rule.getVersion());
        e.setAbTestGroup(rule.getAbTestGroup());
        e.setAbTestTraffic(rule.getAbTestTraffic() != null ?
                BigDecimal.valueOf(rule.getAbTestTraffic()) : null);
        e.setEnabled(rule.isEnabled());
        e.setCreatedAt(rule.getCreatedAt());
        e.setUpdatedAt(rule.getUpdatedAt());
        e.setCreatedBy(rule.getCreatedBy());
        return e;
    }

    private FraudRule toDomain(FraudRuleEntity e) {
        FraudRule rule = new FraudRule();
        rule.setId(e.getId());
        rule.setTenantId(e.getTenantId());
        rule.setRuleName(e.getRuleName());
        rule.setRuleType(RuleType.valueOf(e.getRuleType()));
        try {
            Map<String, Object> dsl = objectMapper.readValue(
                    e.getConditionDsl(), new TypeReference<Map<String, Object>>() {});
            rule.setCondition(new RuleCondition(dsl));
        } catch (Exception ex) {
            throw new RuntimeException("Failed to deserialize rule condition DSL", ex);
        }
        rule.setAction(RuleAction.valueOf(e.getAction()));
        rule.setScoreAdjustment(e.getScoreAdjustment());
        rule.setPriority(e.getPriority());
        rule.setVersion(e.getVersion());
        rule.setAbTestGroup(e.getAbTestGroup());
        rule.setAbTestTraffic(e.getAbTestTraffic() != null ? e.getAbTestTraffic().doubleValue() : null);
        rule.setEnabled(e.isEnabled());
        rule.setCreatedAt(e.getCreatedAt());
        rule.setUpdatedAt(e.getUpdatedAt());
        rule.setCreatedBy(e.getCreatedBy());
        return rule;
    }
}
