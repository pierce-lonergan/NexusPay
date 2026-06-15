package io.nexuspay.b2b.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface JpaVendorPaymentRepository extends JpaRepository<VendorPaymentEntity, String> {
    List<VendorPaymentEntity> findByBatchId(String batchId);
    List<VendorPaymentEntity> findByVendorId(String vendorId);

    // SEC-BATCH-1: tenant-scoped by-id lookup (approval is money-moving — must be tenant-isolated).
    Optional<VendorPaymentEntity> findByIdAndTenantId(String id, String tenantId);
}
