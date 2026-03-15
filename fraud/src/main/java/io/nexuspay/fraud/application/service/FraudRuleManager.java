package io.nexuspay.fraud.application.service;

import io.nexuspay.fraud.application.dto.FraudRuleCreateRequest;
import io.nexuspay.fraud.application.dto.FraudRuleResponse;
import io.nexuspay.fraud.application.dto.FraudRuleUpdateRequest;
import io.nexuspay.fraud.application.port.in.ManageFraudRulesUseCase;
import io.nexuspay.fraud.application.port.out.FraudEventPublisher;
import io.nexuspay.fraud.application.port.out.FraudRuleRepository;
import io.nexuspay.fraud.adapter.out.cache.ValkeyFraudRuleCache;
import io.nexuspay.fraud.domain.model.FraudRule;
import io.nexuspay.fraud.domain.model.RuleCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages fraud rule CRUD operations with cache invalidation and event publishing.
 *
 * @since 0.3.0 (Sprint 3.1)
 */
@Service
public class FraudRuleManager implements ManageFraudRulesUseCase {

    private static final Logger log = LoggerFactory.getLogger(FraudRuleManager.class);

    private final FraudRuleRepository ruleRepository;
    private final ValkeyFraudRuleCache ruleCache;
    private final FraudEventPublisher eventPublisher;

    public FraudRuleManager(FraudRuleRepository ruleRepository,
                             ValkeyFraudRuleCache ruleCache,
                             FraudEventPublisher eventPublisher) {
        this.ruleRepository = ruleRepository;
        this.ruleCache = ruleCache;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Returns active rules for a tenant, sorted by priority (ascending).
     * Serves from Valkey cache when available.
     */
    public List<FraudRule> getActiveRulesForTenant(String tenantId) {
        List<FraudRule> cached = ruleCache.getActiveRules(tenantId);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        List<FraudRule> rules = ruleRepository.findActiveByTenantId(tenantId);
        rules.sort(Comparator.comparingInt(FraudRule::getPriority));
        ruleCache.cacheRules(tenantId, rules);
        return rules;
    }

    @Override
    @Transactional
    public FraudRuleResponse createRule(FraudRuleCreateRequest request, String tenantId, String createdBy) {
        FraudRule rule = new FraudRule(
                UUID.randomUUID(), tenantId, request.ruleName(), request.ruleType(),
                new RuleCondition(request.conditionDsl()), request.action(),
                request.scoreAdjustment(), request.priority(), 1, true, createdBy
        );
        rule.setAbTestGroup(request.abTestGroup());
        rule.setAbTestTraffic(request.abTestTraffic());

        FraudRule saved = ruleRepository.save(rule);
        ruleCache.invalidate(tenantId);

        eventPublisher.publishEvent("FraudRule", saved.getId().toString(),
                "FraudRuleCreated", Map.of(
                        "ruleId", saved.getId().toString(),
                        "ruleName", saved.getRuleName(),
                        "ruleType", saved.getRuleType().name()
                ), tenantId);

        log.info("Fraud rule created: id={}, name={}, type={}, tenant={}",
                saved.getId(), saved.getRuleName(), saved.getRuleType(), tenantId);

        return FraudRuleResponse.from(saved);
    }

    @Override
    @Transactional
    public FraudRuleResponse updateRule(UUID ruleId, FraudRuleUpdateRequest request, String tenantId) {
        FraudRule rule = ruleRepository.findById(ruleId)
                .filter(r -> r.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));

        if (request.conditionDsl() != null) rule.setCondition(new RuleCondition(request.conditionDsl()));
        if (request.action() != null) rule.setAction(request.action());
        if (request.scoreAdjustment() != null) rule.setScoreAdjustment(request.scoreAdjustment());
        if (request.priority() != null) rule.setPriority(request.priority());
        if (request.abTestGroup() != null) rule.setAbTestGroup(request.abTestGroup());
        if (request.abTestTraffic() != null) rule.setAbTestTraffic(request.abTestTraffic());
        if (request.enabled() != null) rule.setEnabled(request.enabled());
        rule.setUpdatedAt(Instant.now());

        FraudRule saved = ruleRepository.save(rule);
        ruleCache.invalidate(tenantId);

        eventPublisher.publishEvent("FraudRule", saved.getId().toString(),
                "FraudRuleUpdated", Map.of(
                        "ruleId", saved.getId().toString(),
                        "ruleName", saved.getRuleName()
                ), tenantId);

        return FraudRuleResponse.from(saved);
    }

    @Override
    @Transactional
    public void disableRule(UUID ruleId, String tenantId) {
        FraudRule rule = ruleRepository.findById(ruleId)
                .filter(r -> r.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));

        rule.setEnabled(false);
        rule.setUpdatedAt(Instant.now());
        ruleRepository.save(rule);
        ruleCache.invalidate(tenantId);

        eventPublisher.publishEvent("FraudRule", ruleId.toString(),
                "FraudRuleDisabled", Map.of("ruleId", ruleId.toString()), tenantId);
    }

    @Override
    @Transactional
    public void enableRule(UUID ruleId, String tenantId) {
        FraudRule rule = ruleRepository.findById(ruleId)
                .filter(r -> r.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));

        rule.setEnabled(true);
        rule.setUpdatedAt(Instant.now());
        ruleRepository.save(rule);
        ruleCache.invalidate(tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public FraudRuleResponse getRule(UUID ruleId, String tenantId) {
        return ruleRepository.findById(ruleId)
                .filter(r -> r.getTenantId().equals(tenantId))
                .map(FraudRuleResponse::from)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + ruleId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FraudRuleResponse> listRules(String tenantId) {
        return ruleRepository.findByTenantId(tenantId).stream()
                .map(FraudRuleResponse::from)
                .toList();
    }
}
