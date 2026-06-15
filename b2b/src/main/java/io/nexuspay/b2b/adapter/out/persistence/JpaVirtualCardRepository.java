package io.nexuspay.b2b.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface JpaVirtualCardRepository extends JpaRepository<VirtualCardEntity, String> {
    List<VirtualCardEntity> findByTenantId(String tenantId);

    // SEC-BATCH-1: tenant-scoped by-id lookup.
    Optional<VirtualCardEntity> findByIdAndTenantId(String id, String tenantId);
}
