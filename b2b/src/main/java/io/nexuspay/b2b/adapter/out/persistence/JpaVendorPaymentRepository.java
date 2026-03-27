package io.nexuspay.b2b.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface JpaVendorPaymentRepository extends JpaRepository<VendorPaymentEntity, String> {
    List<VendorPaymentEntity> findByBatchId(String batchId);
    List<VendorPaymentEntity> findByVendorId(String vendorId);
}
