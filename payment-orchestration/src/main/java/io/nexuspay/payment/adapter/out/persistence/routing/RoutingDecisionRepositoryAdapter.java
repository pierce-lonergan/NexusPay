package io.nexuspay.payment.adapter.out.persistence.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.application.port.routing.RoutingDecisionRepository;
import io.nexuspay.payment.domain.routing.RoutingDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing RoutingDecisionRepository port via JPA.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Component
public class RoutingDecisionRepositoryAdapter implements RoutingDecisionRepository {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingDecisionRepositoryAdapter.class);

    private final JpaRoutingDecisionRepository jpaRepo;
    private final ObjectMapper objectMapper;

    public RoutingDecisionRepositoryAdapter(JpaRoutingDecisionRepository jpaRepo, ObjectMapper objectMapper) {
        this.jpaRepo = jpaRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public RoutingDecision save(RoutingDecision decision) {
        RoutingDecisionEntity entity = toEntity(decision);
        RoutingDecisionEntity saved = jpaRepo.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<RoutingDecision> findById(UUID id) {
        return jpaRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<RoutingDecision> findByPaymentId(String paymentId) {
        return jpaRepo.findByPaymentId(paymentId).map(this::toDomain);
    }

    @Override
    public List<RoutingDecision> findByAbTestId(UUID abTestId) {
        return jpaRepo.findByAbTestId(abTestId).stream()
                .map(this::toDomain)
                .toList();
    }

    private RoutingDecisionEntity toEntity(RoutingDecision decision) {
        RoutingDecisionEntity entity = new RoutingDecisionEntity();
        entity.setId(decision.id());
        entity.setTenantId(decision.tenantId());
        entity.setPaymentId(decision.paymentId());
        entity.setStrategyUsed(decision.strategyUsed());
        entity.setConfigId(decision.configId());
        entity.setSelectedPsp(decision.selectedPsp());
        entity.setCandidateScores(serializeMap(decision.candidateScores()));
        entity.setCascadeDepth(decision.cascadeOrder().size());
        entity.setCascadePsps(serializeList(decision.cascadeOrder()));
        entity.setAbTestId(decision.abTestId());
        entity.setAbTestGroup(decision.abTestGroup());
        entity.setDecidedAt(decision.decidedAt());
        entity.setDecisionLatencyMs((int) decision.decisionLatencyMs());
        return entity;
    }

    private RoutingDecision toDomain(RoutingDecisionEntity entity) {
        return new RoutingDecision(
                entity.getId(),
                entity.getTenantId(),
                entity.getPaymentId(),
                entity.getStrategyUsed(),
                entity.getConfigId(),
                entity.getSelectedPsp(),
                deserializeMap(entity.getCandidateScores()),
                deserializeList(entity.getCascadePsps()),
                entity.getAbTestId(),
                entity.getAbTestGroup(),
                entity.getDecidedAt(),
                entity.getDecisionLatencyMs()
        );
    }

    private String serializeMap(Map<String, Double> map) {
        try {
            return objectMapper.writeValueAsString(map != null ? map : Map.of());
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize candidate scores: {}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, Double> deserializeMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to deserialize candidate scores: {}", e.getMessage());
            return Map.of();
        }
    }

    private String serializeList(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list != null ? list : List.of());
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize cascade PSPs: {}", e.getMessage());
            return "[]";
        }
    }

    private List<String> deserializeList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to deserialize cascade PSPs: {}", e.getMessage());
            return List.of();
        }
    }
}
