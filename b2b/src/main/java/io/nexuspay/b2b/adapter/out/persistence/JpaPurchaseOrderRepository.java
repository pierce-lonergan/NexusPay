package io.nexuspay.b2b.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface JpaPurchaseOrderRepository extends JpaRepository<PurchaseOrderEntity, String> {
    List<PurchaseOrderEntity> findByTenantId(String tenantId);

    // SEC-23: tenant-scoped by-id lookup.
    Optional<PurchaseOrderEntity> findByIdAndTenantId(String id, String tenantId);
}
