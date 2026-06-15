package io.nexuspay.marketplace.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for connected accounts.
 *
 * @since 0.4.1 (Sprint 4.2)
 */
public interface JpaConnectedAccountRepository extends JpaRepository<ConnectedAccountEntity, String> {

    List<ConnectedAccountEntity> findByTenantId(String tenantId);

    // SEC-BATCH-1: tenant-scoped by-id lookup (predicate pushed to SQL — no foreign-tenant row loaded).
    Optional<ConnectedAccountEntity> findByIdAndTenantId(String id, String tenantId);
}
