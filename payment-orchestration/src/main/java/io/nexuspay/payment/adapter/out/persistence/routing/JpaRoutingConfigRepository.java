package io.nexuspay.payment.adapter.out.persistence.routing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for routing config entities.
 *
 * @since 0.3.0 (Sprint 3.3)
 */
@Repository
public interface JpaRoutingConfigRepository extends JpaRepository<RoutingConfigEntity, UUID> {

    @Query("""
            SELECT c FROM RoutingConfigEntity c
            WHERE c.tenantId = :tenantId AND c.enabled = true
            ORDER BY c.updatedAt DESC
            """)
    Optional<RoutingConfigEntity> findActiveByTenant(@Param("tenantId") String tenantId);

    List<RoutingConfigEntity> findByTenantId(String tenantId);
}
