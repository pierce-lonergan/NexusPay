package io.nexuspay.payment.adapter.out.persistence.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nexuspay.payment.application.port.routing.RoutingConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing RoutingConfigRepository port via JPA.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Component
public class RoutingConfigRepositoryAdapter implements RoutingConfigRepository {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingConfigRepositoryAdapter.class);

    private final JpaRoutingConfigRepository jpaRepo;
    private final ObjectMapper objectMapper;

    public RoutingConfigRepositoryAdapter(JpaRoutingConfigRepository jpaRepo, ObjectMapper objectMapper) {
        this.jpaRepo = jpaRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    public RoutingConfig save(RoutingConfig config) {
        RoutingConfigEntity entity = toEntity(config);
        RoutingConfigEntity saved = jpaRepo.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<RoutingConfig> findById(UUID id) {
        return jpaRepo.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<RoutingConfig> findActiveByTenant(String tenantId) {
        return jpaRepo.findActiveByTenant(tenantId).map(this::toDomain);
    }

    @Override
    public List<RoutingConfig> findByTenantId(String tenantId) {
        return jpaRepo.findByTenantId(tenantId).stream()
                .map(this::toDomain)
                .toList();
    }

    private RoutingConfigEntity toEntity(RoutingConfig config) {
        RoutingConfigEntity entity = new RoutingConfigEntity();
        entity.setId(config.id());
        entity.setTenantId(config.tenantId());
        entity.setConfigName(config.configName());
        entity.setStrategy(config.strategy());
        entity.setPspList(serializeList(config.pspList()));
        entity.setCascadeEnabled(config.cascadeEnabled());
        entity.setMaxCascadeDepth(config.maxCascadeDepth());
        entity.setAbTestId(config.abTestId());
        entity.setAbTestTraffic(config.abTestTraffic());
        entity.setEnabled(config.enabled());
        entity.setCreatedAt(config.createdAt());
        entity.setUpdatedAt(config.updatedAt());
        return entity;
    }

    private RoutingConfig toDomain(RoutingConfigEntity entity) {
        return new RoutingConfig(
                entity.getId(),
                entity.getTenantId(),
                entity.getConfigName(),
                entity.getStrategy(),
                deserializeList(entity.getPspList()),
                entity.isCascadeEnabled(),
                entity.getMaxCascadeDepth(),
                entity.getAbTestId(),
                entity.getAbTestTraffic() != null ? entity.getAbTestTraffic() : 0.0,
                entity.isEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String serializeList(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list != null ? list : List.of());
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize PSP list: {}", e.getMessage());
            return "[]";
        }
    }

    private List<String> deserializeList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to deserialize PSP list: {}", e.getMessage());
            return List.of();
        }
    }
}
