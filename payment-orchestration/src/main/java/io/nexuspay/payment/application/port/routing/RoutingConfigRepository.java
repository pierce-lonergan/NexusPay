package io.nexuspay.payment.application.port.routing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for tenant routing configurations.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
public interface RoutingConfigRepository {

    RoutingConfig save(RoutingConfig config);

    Optional<RoutingConfig> findById(UUID id);

    /**
     * SEC-27: tenant-scoped by-id lookup. Empty result means "absent OR owned by another tenant" —
     * pair with {@code TenantOwnership.require} so a foreign id 404s without an existence oracle.
     */
    Optional<RoutingConfig> findByIdAndTenantId(UUID id, String tenantId);

    Optional<RoutingConfig> findActiveByTenant(String tenantId);

    List<RoutingConfig> findByTenantId(String tenantId);

    /**
     * Tenant routing configuration.
     */
    record RoutingConfig(
            UUID id,
            String tenantId,
            String configName,
            String strategy,
            List<String> pspList,
            boolean cascadeEnabled,
            int maxCascadeDepth,
            UUID abTestId,
            double abTestTraffic,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static RoutingConfig defaults(String tenantId) {
            return new RoutingConfig(
                    UUID.randomUUID(), tenantId, "default", "SUCCESS_RATE",
                    List.of(), true, 3, null, 0.0, true,
                    Instant.now(), Instant.now()
            );
        }
    }
}
