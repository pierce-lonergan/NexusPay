package io.nexuspay.b2b.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface JpaB2bInvoiceRepository extends JpaRepository<B2bInvoiceEntity, String> {
    Optional<B2bInvoiceEntity> findByPurchaseOrderId(String purchaseOrderId);
}
